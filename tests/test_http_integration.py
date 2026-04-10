"""HTTP-level integration tests for MCP token management.

Tests the complete stack:
  HTTP request -> AuthMiddleware -> _AcceptMiddleware -> _IdentityMiddleware
  -> _ToolGateMiddleware -> FastMCP -> PostgreSQL

Uses httpx.AsyncClient with ASGI transport against the real middleware stack.
"""

import asyncio
import json

import httpx
import pytest

from hivemem.security import (
    ADMIN_TOOLS,
    ALL_TOOLS,
    READ_TOOLS,
    WRITE_TOOLS,
    AuthMiddleware,
    create_token,
    revoke_token,
)
from hivemem.server import (
    _AcceptMiddleware,
    _IdentityMiddleware,
    _ToolGateMiddleware,
    mcp,
)


# ── Helpers ───────────────────────────────────────────────────────────────


MCP_HEADERS = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}


def _jsonrpc(method: str, params: dict | None = None, req_id: int = 1) -> dict:
    """Build a JSON-RPC 2.0 request body."""
    body: dict = {"jsonrpc": "2.0", "id": req_id, "method": method}
    if params is not None:
        body["params"] = params
    return body


def _init_request() -> dict:
    """MCP initialize handshake request."""
    return _jsonrpc("initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "test", "version": "1.0"},
    })


async def _mcp_call(
    client: httpx.AsyncClient,
    token: str,
    method: str,
    params: dict | None = None,
    req_id: int = 1,
) -> dict:
    """Send a JSON-RPC request to /mcp and return parsed JSON response.

    Handles the MCP initialize handshake and extracts the response from
    either plain JSON or SSE format.
    """
    headers = {**MCP_HEADERS, "Authorization": f"Bearer {token}"}

    # Initialize session first
    init_resp = await client.post("/mcp", json=_init_request(), headers=headers)
    session_id = init_resp.headers.get("mcp-session-id")
    if session_id:
        headers["Mcp-Session-Id"] = session_id

    # Send initialized notification
    notif = {"jsonrpc": "2.0", "method": "notifications/initialized"}
    await client.post("/mcp", json=notif, headers=headers)

    # Actual request
    resp = await client.post(
        "/mcp", json=_jsonrpc(method, params, req_id), headers=headers,
    )
    return _parse_mcp_response(resp)


def _parse_mcp_response(resp: httpx.Response) -> dict:
    """Parse an MCP response that may be plain JSON or SSE."""
    ct = resp.headers.get("content-type", "")
    if "text/event-stream" in ct:
        # Parse SSE: look for data lines with JSON-RPC response
        for line in resp.text.splitlines():
            if line.startswith("data: "):
                try:
                    data = json.loads(line[6:])
                    if isinstance(data, dict) and ("result" in data or "error" in data):
                        return data
                except json.JSONDecodeError:
                    continue
        return {"error": f"Could not parse SSE response: {resp.text[:500]}"}
    else:
        return resp.json()


class _LifespanASGIWrapper:
    """ASGI wrapper that sends lifespan startup to the inner Starlette app
    on the first HTTP request, and shutdown when closed.

    This solves the problem that httpx.ASGITransport does not send ASGI
    lifespan events, but FastMCP's StreamableHTTPSessionManager requires
    its task group to be initialized via the Starlette lifespan.
    """

    def __init__(self, inner_app, outer_app):
        self.inner_app = inner_app  # Starlette app that needs lifespan
        self.outer_app = outer_app  # Full middleware stack to handle HTTP
        self._started = False
        self._lifespan_task = None
        self._startup_complete = None
        self._shutdown_event = None

    async def _run_lifespan(self):
        """Run the ASGI lifespan protocol against the inner app."""
        startup_complete = self._startup_complete
        shutdown_event = self._shutdown_event

        async def receive():
            # First call returns startup
            if not hasattr(receive, '_sent_startup'):
                receive._sent_startup = True
                return {"type": "lifespan.startup"}
            # Then wait for shutdown signal
            await shutdown_event.wait()
            return {"type": "lifespan.shutdown"}

        async def send(message):
            if message["type"] == "lifespan.startup.complete":
                startup_complete.set()
            elif message["type"] == "lifespan.startup.failed":
                startup_complete.set()

        scope = {"type": "lifespan", "asgi": {"version": "3.0"}}
        await self.inner_app(scope, receive, send)

    async def start(self):
        """Start the lifespan of the inner app."""
        if self._started:
            return
        self._startup_complete = asyncio.Event()
        self._shutdown_event = asyncio.Event()
        self._lifespan_task = asyncio.create_task(self._run_lifespan())
        await self._startup_complete.wait()
        self._started = True

    async def stop(self):
        """Stop the lifespan of the inner app."""
        if not self._started:
            return
        self._shutdown_event.set()
        try:
            await asyncio.wait_for(self._lifespan_task, timeout=5.0)
        except (asyncio.TimeoutError, Exception):
            self._lifespan_task.cancel()
            try:
                await self._lifespan_task
            except (asyncio.CancelledError, Exception):
                pass
        self._started = False

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            # Don't forward lifespan from httpx — we handle it ourselves
            return
        if not self._started:
            await self.start()
        await self.outer_app(scope, receive, send)


# ── Fixtures ──────────────────────────────────────────────────────────────


@pytest.fixture
async def mcp_app(pool, db_url):
    """Build full ASGI middleware stack with test pool.

    Uses a lifespan wrapper to initialize the StreamableHTTPSessionManager
    task group, which httpx ASGITransport would otherwise skip.
    """
    import hivemem.server as srv

    original_pool = srv._pool_cache
    srv._pool_cache = pool

    # Get the Starlette app (creates session manager lazily)
    starlette_app = mcp.streamable_http_app()

    # Wrap with middleware stack (same order as production __main__)
    full_app = AuthMiddleware(
        _AcceptMiddleware(
            _IdentityMiddleware(
                _ToolGateMiddleware(starlette_app)
            )
        ),
        pool=pool,
    )

    wrapper = _LifespanASGIWrapper(starlette_app, full_app)
    await wrapper.start()

    yield wrapper

    await wrapper.stop()
    mcp._session_manager = None
    srv._pool_cache = original_pool


@pytest.fixture
async def client(mcp_app):
    """httpx AsyncClient wired to the ASGI app."""
    transport = httpx.ASGITransport(app=mcp_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.fixture
async def admin_token(pool):
    return await create_token(pool, "http-admin", "admin")


@pytest.fixture
async def reader_token(pool):
    return await create_token(pool, "http-reader", "reader")


@pytest.fixture
async def writer_token(pool):
    return await create_token(pool, "http-writer", "writer")


@pytest.fixture
async def agent_token(pool):
    return await create_token(pool, "http-agent", "agent")


# ── 1. Auth Layer Tests ──────────────────────────────────────────────────


class TestAuthLayer:
    """Verify Bearer token authentication at the HTTP level."""

    async def test_no_token_returns_401(self, client):
        resp = await client.post("/mcp", json=_init_request(), headers=MCP_HEADERS)
        assert resp.status_code == 401
        assert "token" in resp.json()["error"].lower()

    async def test_invalid_token_returns_401(self, client):
        headers = {**MCP_HEADERS, "Authorization": "Bearer totally-bogus-token"}
        resp = await client.post("/mcp", json=_init_request(), headers=headers)
        assert resp.status_code == 401

    async def test_valid_admin_token_succeeds(self, client, admin_token):
        headers = {**MCP_HEADERS, "Authorization": f"Bearer {admin_token}"}
        resp = await client.post("/mcp", json=_init_request(), headers=headers)
        assert resp.status_code == 200

    async def test_revoked_token_returns_401(self, client, pool, admin_token):
        # Verify the token works first
        headers = {**MCP_HEADERS, "Authorization": f"Bearer {admin_token}"}
        resp = await client.post("/mcp", json=_init_request(), headers=headers)
        assert resp.status_code == 200

        # Revoke it
        await revoke_token(pool, "http-admin")

        # Clear the middleware auth cache so revocation is visible immediately
        for layer in _unwrap_middleware(client):
            if isinstance(layer, AuthMiddleware):
                layer._cache.clear()

        resp = await client.post("/mcp", json=_init_request(), headers=headers)
        assert resp.status_code == 401

    async def test_expired_token_returns_401(self, client, pool):
        """Create a token that is already expired, then try to use it."""
        from hivemem.db import execute

        import hashlib
        import secrets as _secrets

        plaintext = _secrets.token_urlsafe(32)
        token_hash = hashlib.sha256(plaintext.encode()).hexdigest()

        # Insert token with expires_at in the past
        await execute(
            pool,
            "INSERT INTO api_tokens (token_hash, name, role, expires_at) "
            "VALUES (%s, %s, %s, now() - interval '1 hour')",
            (token_hash, "http-expired", "admin"),
        )

        headers = {**MCP_HEADERS, "Authorization": f"Bearer {plaintext}"}
        resp = await client.post("/mcp", json=_init_request(), headers=headers)
        assert resp.status_code == 401


def _unwrap_middleware(client: httpx.AsyncClient):
    """Walk the middleware chain and yield each layer."""
    app = client._transport.app  # type: ignore[attr-defined]
    while app is not None:
        yield app
        # _LifespanASGIWrapper stores the middleware stack in outer_app
        app = getattr(app, "outer_app", None) or getattr(app, "app", None)


# ── 2. Tool Visibility Tests (tools/list filtered by role) ───────────────


class TestToolVisibility:
    """Verify that tools/list is filtered by token role."""

    async def test_admin_sees_all_tools(self, client, admin_token):
        data = await _mcp_call(client, admin_token, "tools/list")
        tools = data["result"]["tools"]
        names = {t["name"] for t in tools}
        assert names == ALL_TOOLS, f"Missing: {ALL_TOOLS - names}, Extra: {names - ALL_TOOLS}"

    async def test_reader_sees_only_read_tools(self, client, reader_token):
        data = await _mcp_call(client, reader_token, "tools/list")
        tools = data["result"]["tools"]
        names = {t["name"] for t in tools}
        assert names == READ_TOOLS, f"Missing: {READ_TOOLS - names}, Extra: {names - READ_TOOLS}"

    async def test_writer_sees_read_and_write_tools(self, client, writer_token):
        data = await _mcp_call(client, writer_token, "tools/list")
        tools = data["result"]["tools"]
        names = {t["name"] for t in tools}
        expected = READ_TOOLS | WRITE_TOOLS
        assert names == expected, f"Missing: {expected - names}, Extra: {names - expected}"

    async def test_agent_sees_same_as_writer(self, client, agent_token):
        data = await _mcp_call(client, agent_token, "tools/list")
        tools = data["result"]["tools"]
        names = {t["name"] for t in tools}
        expected = READ_TOOLS | WRITE_TOOLS
        assert names == expected, f"Missing: {expected - names}, Extra: {names - expected}"


# ── 3. Tool Execution Through HTTP ──────────────────────────────────────


class TestToolExecution:
    """Verify tools can be called through the full HTTP stack."""

    async def test_admin_calls_status(self, client, admin_token):
        data = await _mcp_call(
            client, admin_token, "tools/call",
            {"name": "hivemem_status", "arguments": {}},
        )
        assert "result" in data, f"Expected result, got: {data}"
        content = data["result"]["content"]
        assert len(content) > 0
        parsed = json.loads(content[0]["text"])
        assert "wings" in parsed

    async def test_reader_calls_search(self, client, reader_token):
        data = await _mcp_call(
            client, reader_token, "tools/call",
            {"name": "hivemem_search", "arguments": {"query": "test query"}},
        )
        assert "result" in data, f"Expected result, got: {data}"
        # Search on an empty DB returns a valid result with empty content
        # or a single text item containing "[]".  Either is acceptable.
        content = data["result"]["content"]
        if len(content) > 0:
            parsed = json.loads(content[0]["text"])
            assert isinstance(parsed, list)
        else:
            # Empty content list is valid for zero results
            assert content == []

    async def test_agent_add_drawer_creates_pending(self, client, agent_token, pool):
        data = await _mcp_call(
            client, agent_token, "tools/call",
            {"name": "hivemem_add_drawer", "arguments": {
                "content": "Agent HTTP integration test drawer",
                "wing": "tests",
                "room": "http",
            }},
        )
        assert "result" in data, f"Expected result, got: {data}"
        content = data["result"]["content"]
        parsed = json.loads(content[0]["text"])
        drawer_id = parsed["id"]

        # Verify it was created as pending
        from hivemem.db import fetch_one

        row = await fetch_one(
            pool,
            "SELECT status, created_by FROM drawers WHERE id = %s",
            (drawer_id,),
        )
        assert row["status"] == "pending"
        assert row["created_by"] == "http-agent"

    async def test_writer_add_drawer_creates_committed(self, client, writer_token, pool):
        data = await _mcp_call(
            client, writer_token, "tools/call",
            {"name": "hivemem_add_drawer", "arguments": {
                "content": "Writer HTTP integration test drawer",
                "wing": "tests",
                "room": "http",
            }},
        )
        assert "result" in data, f"Expected result, got: {data}"
        content = data["result"]["content"]
        parsed = json.loads(content[0]["text"])
        drawer_id = parsed["id"]

        from hivemem.db import fetch_one

        row = await fetch_one(
            pool,
            "SELECT status, created_by FROM drawers WHERE id = %s",
            (drawer_id,),
        )
        assert row["status"] == "committed"
        assert row["created_by"] == "http-writer"


# ── 4. Admin-Only Enforcement Through HTTP ───────────────────────────────


class TestAdminEnforcement:
    """Verify non-admin roles cannot see or call admin-only tools."""

    async def test_writer_cannot_see_admin_tools(self, client, writer_token):
        data = await _mcp_call(client, writer_token, "tools/list")
        tools = data["result"]["tools"]
        names = {t["name"] for t in tools}
        for admin_tool in ADMIN_TOOLS:
            assert admin_tool not in names, f"Writer should not see {admin_tool}"

    async def test_reader_cannot_see_write_tools(self, client, reader_token):
        data = await _mcp_call(client, reader_token, "tools/list")
        tools = data["result"]["tools"]
        names = {t["name"] for t in tools}
        for write_tool in WRITE_TOOLS:
            assert write_tool not in names, f"Reader should not see {write_tool}"
        for admin_tool in ADMIN_TOOLS:
            assert admin_tool not in names, f"Reader should not see {admin_tool}"
