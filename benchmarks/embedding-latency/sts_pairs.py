"""STS-Benchmark sentence pairs with gold similarity scores.

A 24-pair sample drawn from the public STS-Benchmark (SemEval 2017) dev and
test splits. Gold scores are human-annotated on a 0-5 continuous scale:

    0 = The two sentences are completely dissimilar.
    5 = The two sentences are completely equivalent, meaning the same thing.

Source: https://ixa2.si.ehu.eus/stswiki/index.php/STSbenchmark
License of STS-Benchmark: CC BY-SA 4.0.

This sample is intentionally small so the benchmark stays dependency-free and
fast; it is enough for a relative quality signal (per-release trend) but not
a substitute for a full MTEB run.
"""

from __future__ import annotations

# (sentence_a, sentence_b, gold_score_0_to_5)
STS_PAIRS: list[tuple[str, str, float]] = [
    ("A plane is taking off.", "An air plane is taking off.", 5.00),
    ("A man is playing a large flute.", "A man is playing a flute.", 3.80),
    (
        "A man is spreading shreded cheese on a pizza.",
        "A man is spreading shredded cheese on an uncooked pizza.",
        3.80,
    ),
    ("Three men are playing chess.", "Two men are playing chess.", 2.60),
    ("A man is playing the cello.", "A man seated is playing the cello.", 4.25),
    ("Some men are fighting.", "Two men are fighting.", 4.25),
    ("A man is smoking.", "A man is skating.", 0.50),
    ("The man is playing the piano.", "The man is playing the guitar.", 1.60),
    (
        "A man is playing on a guitar and singing.",
        "A woman is playing an acoustic guitar and singing.",
        2.20,
    ),
    (
        "A person is throwing a cat on to the ceiling.",
        "A person throws a cat on the ceiling.",
        5.00,
    ),
    ("The woman is playing the violin.", "The young lady enjoys listening to the guitar.", 1.00),
    ("Three men are playing guitars.", "Three men are on stage playing guitars.", 3.75),
    ("A man is cutting up a cucumber.", "A man is slicing a cucumber.", 4.25),
    ("A panda is sliding down a slide.", "A panda bear is walking.", 1.50),
    ("A woman is dancing in the rain.", "A woman dances in the rain in the street.", 3.80),
    (
        "A black dog is running through the water.",
        "A black dog is running through water with a ball in its mouth.",
        3.60,
    ),
    ("A cat is playing a piano.", "A man is playing a guitar.", 0.60),
    ("A young child is riding a horse.", "A child is riding a horse.", 4.75),
    ("A woman is slicing an onion.", "A woman is cutting an onion.", 4.60),
    ("A man is pouring oil into a pan.", "A woman is pouring oil in a pan.", 3.80),
    ("A man is riding a bicycle.", "A man is riding a bike.", 5.00),
    ("A tiger walks around aimlessly.", "A tiger is pacing around a cage.", 2.20),
    ("Two dogs play in the grass.", "Two dogs are playing in a field.", 4.40),
    ("A man is dancing.", "A man is talking.", 0.60),
]
