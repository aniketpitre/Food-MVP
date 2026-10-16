# People's Pick: how FoodFeed decides what you should eat

The question FoodFeed answers is "I want pav bhaji, where should I go?". Star ratings
don't answer it: they rate a whole restaurant, and anyone can leave one. FoodFeed ranks
**one dish at one place** by **what people who actually tried it decided**, and it trusts
some people more than others because of their track record.

## Votes

| Vote | Meaning |
|---|---|
| **Loved it** (recommend) | "Go here for this." |
| **Not for me** (pass) | "I tried it and wouldn't send you there." Also marks it tried. |
| Tried, no verdict | Ignored by the rating. |
| Save, share | Feed popularity only; never part of the rating. |

Loved it and Not for me exclude each other.

## Trust: whose vote counts more

When you love a dish, everyone who votes on that dish **after you** tests your call. For
each dish you loved, the share of later voters who also loved it is your agreement for
that pick. Per dish type (misal, biryani, chai, ...) and overall:

```
trust = 0.6 + 0.8 × (agreement + 1) / (picks + 2)
```

* `picks` = dishes you loved that others voted on afterwards; `agreement` = sum of the
  later approval shares. The `+1 / +2` (Laplace smoothing) keeps one lucky pick from
  making anyone an expert: a newcomer starts at **1.0**, the range is **0.6 to 1.4**.
* Being early and right is what earns trust: loving a dish after it is already famous
  adds little, because few people vote after you.
* "Expert" badge: at least 3 picks of that type and trust ≥ 1.1.

## The rating of a dish

Each vote is weighted by the voter's trust for that dish type, halved for votes made in
an account's first 24 hours, and faded with a 180-day half-life. Then:

```
rating     = (weighted loves + 3 × 0.75) / (weighted loves + weighted passes + 3)
confidence = rating − 1.0 × sqrt(rating × (1 − rating) / (weighted votes + 3))
```

* The prior (as if 3 votes at 75 %) stops a single love from scoring 100 %.
* Dishes are ranked by **confidence**, a cautious lower estimate, so a dish needs both a
  high share of loves and enough votes to reach the top. 5 loves and 0 passes beats
  28 loves and 6 passes; 1 love does not beat 30 loves.
* People see the plain numbers: "82 % loved it · 34 votes", plus the **champion**, the
  most trusted person who loved it ("Loved by @diya_k, misal expert").

## Searching a craving

Type "paav bhaji", "pavbhaji" or "pav bhaji": spellings resolve to the same dish, and dish
types ("biryani") return every dish of that type. Results are ordered by confidence, and
the people with the highest trust for those dishes are listed as **people to trust**.

## Tuning

Every number above lives in the `ranking_config` table (`pp_*`, `trust_*`, `expert_*`)
and can be changed without shipping the app. The SQL is `refresh_people_pick()` in
`backend/migrations/003_social_and_peoples_pick.sql`; it runs after every vote and on
the ranking timer.
