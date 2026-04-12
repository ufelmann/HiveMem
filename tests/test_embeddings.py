"""Tests for embedding layer."""

import pytest
from hivemem.embeddings import get_model, encode, get_dimension


def test_encode_returns_correct_dimensions():
    text = "Alice leads the engineering team at Acme Corp"
    vector = encode(text)
    assert len(vector) == get_dimension()
    assert isinstance(vector[0], float)


def test_encode_returns_dense_and_sparse():
    text = "Alice leads the engineering team at Acme Corp"
    result = encode(text, return_sparse=True)
    assert "dense" in result
    assert "sparse" in result
    assert len(result["dense"]) == get_dimension()


def test_encode_similar_texts_are_close():
    v1 = encode("GraphQL API migration")
    v2 = encode("GraphQL schema change")
    v3 = encode("Sauna electrical installation")

    from numpy import dot
    from numpy.linalg import norm
    sim_related = dot(v1, v2) / (norm(v1) * norm(v2))
    sim_unrelated = dot(v1, v3) / (norm(v1) * norm(v3))

    assert sim_related > sim_unrelated


def test_encode_german_text():
    vector = encode("Das Team hat die neue Architektur besprochen")
    assert len(vector) == get_dimension()


def test_get_model_singleton():
    m1 = get_model()
    m2 = get_model()
    assert m1 is m2
