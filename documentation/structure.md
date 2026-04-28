# The Structure

HiveMem organizes knowledge in a spatial hierarchy that is easy to navigate. Realms, signals, topics, and cells -- four levels from broad to specific. Tunnels connect cells across the entire structure, revealing hidden relationships in your knowledge.

```mermaid
graph TB
    subgraph HM["HiveMem"]
        direction TB

        subgraph Realm1["Realm: Projects"]
            direction TB
            subgraph Signal1["Signal: Software"]
                direction LR
                subgraph Topic1A["Topic: HiveMem"]
                    D1["Cell<br/><i>content</i><br/><i>summary</i><br/><i>key points</i><br/><i>insight</i>"]
                    D2["Cell"]
                end
                subgraph Topic1B["Topic: Website"]
                    D3["Cell"]
                end
            end
        end

        subgraph Realm2["Realm: Knowledge"]
            direction TB
            subgraph Signal2["Signal: Tech"]
                direction LR
                subgraph Topic2A["Topic: AI"]
                    D5["Cell"]
                    D6["Cell"]
                end
                subgraph Topic2B["Topic: Security"]
                    D7["Cell"]
                end
            end
        end
    end

    D1 <-..->|"builds_on"| D5
    D2 <-..->|"related_to"| D3
    D6 <-..->|"contradicts"| D7

    subgraph KG["Knowledge Graph"]
        F1["Fact<br/><i>subject _ predicate _ object</i><br/><i>valid_from / valid_until</i>"]
    end

    subgraph BP["Blueprint"]
        M1["Narrative overview<br/><i>per realm</i>"]
    end

    D1 -.->|"source"| F1
    Realm1 -.-> M1

    classDef realm fill:#4a90d9,stroke:#2c5f8a,color:white
    classDef signal fill:#5ba85b,stroke:#3d7a3d,color:white
    classDef topic fill:#e8a838,stroke:#b8802a,color:white
    classDef cell fill:#f5f5f5,stroke:#999,color:#333
    classDef kg fill:#c0392b,stroke:#962d22,color:white
    classDef bp fill:#9b59b6,stroke:#7d3c98,color:white
    classDef hm fill:#f0f4f8,stroke:#4a90d9,color:#333

    class Realm1,Realm2 realm
    class Signal1,Signal2 signal
    class Topic1A,Topic1B,Topic2A,Topic2B topic
    class D1,D2,D3,D5,D6,D7 cell
    class KG,F1 kg
    class BP,M1 bp
    class HM hm
```

## Concepts

| Concept | Description | Example |
|---|---|---|
| **Realm** | Top-level category | "Projects", "Knowledge", "Cooking" |
| **Signal** | A signal within a realm | "Software", "Italian Cuisine" |
| **Topic** | A topic within a signal | "HiveMem", "Pasta Recipes" |
| **Cell** | Single knowledge item with content, summary, key points, and insight | A design decision, a recipe, a meeting note |
| **Tunnel** | Passage connecting two cells | `builds_on`, `related_to`, `contradicts`, `refines` |
| **Fact** | Atomic knowledge triple in the knowledge graph | "HiveMem → uses → PostgreSQL" with temporal validity |
| **Blueprint** | Narrative overview of a realm | How signals, topics, and key cells in a realm connect |

## How It Works

1. **Store** -- Content is classified into realm/signal/topic and stored as a cell with progressive summarization (content, summary, key points, insight)
2. **Connect** -- Tunnels link related cells across the structure; facts capture atomic relationships in the knowledge graph
3. **Search** -- 6-signal ranked search finds cells by meaning, keywords, recency, importance, popularity, and graph proximity
4. **Traverse** -- Follow tunnels to discover hidden connections; use time machine to see what was known at any point
5. **Wake up** -- Each session starts with identity context and critical facts, like navigating back to your knowledge and remembering where everything is
