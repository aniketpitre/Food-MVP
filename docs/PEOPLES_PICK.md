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

## Standing: one real person, one vote

Every vote, for People's Pick and for the feed, is multiplied by its account's
**standing** (0 to 1):

```
standing = min(1, account age / 7 days) / (1 + lockstep partners)
```

* A new account grows to full weight over its first week: an hour-old account counts
  about 1/168. This is applied after the per-person cap, so a burst of fresh accounts
  can't fill a dish's score.
* Two accounts are **lockstep partners** when most of their votes are on the same dishes,
  the same way, within 2 hours of each other (at least 2 such votes, covering 80 % of the
  smaller account's votes). A ring of six accounts voting together counts as one person,
  can't build each other's trust, and can't produce a champion or an expert.
* Lane minimums (Top: 3 people, Trending: 2, Hidden gems: 5) count people by standing.

## Trust: whose vote counts more

Every **love** and every **not for me** is a pick. Everyone who votes on that dish
**after you** tests your call, counted by their standing (your own lockstep partners
don't count). A pick earns credit only for agreement **beyond what always voting that
way would get**: approval is usually around 75 %, so loving a dish that 75 % love earns
nothing, loving one that 100 % love earns 1, and a "not for me" that later voters agree
with earns credit too. Per dish type (misal, biryani, chai, ...) and overall:

```
trust = 0.6 + 0.8 × credit / (picks + 2)
```

* `picks` counts each pick by how much independent backing it got (up to 1). The `+2`
  means a record starts as two picks nobody backed: a newcomer starts at the floor
  (**0.6**), the same as someone whose picks earned nothing. Nobody outranks a proven
  record just by being new.
* Being early and right is what earns trust: loving a dish after it is already famous
  adds little, because few people vote after you.
* "Expert" badge: at least 3 picks of that type, trust ≥ 1.05 and full standing.

## The rating of a dish

Each vote is weighted by the voter's trust for that dish type × their standing, faded with
a 180-day half-life. Then:

```
rating     = (weighted loves + 3 × 0.75) / (weighted loves + weighted passes + 3)
confidence = rating − 1.0 × sqrt(rating × (1 − rating) / (weighted votes + 3))
```

* The prior (as if 3 votes at 75 %) stops a single love from scoring 100 %, and a dish
  nobody has voted on sits at the prior (confidence 0.5), above a dish people dislike.
* Dishes are ranked by **confidence**, a cautious lower estimate, so a dish needs both a
  high share of loves and enough votes to reach the top. 5 loves and 0 passes beats
  28 loves and 6 passes; 1 love does not beat 30 loves.
* People see the plain numbers: "82 % loved it · 34 votes", plus the **champion**, the
  most trusted person in good standing who loved it ("Loved by @diya_k, misal expert").
  A champion you've blocked isn't shown to you.

## Searching a craving

Type "paav bhaji", "pavbhaji" or "pav bhaji": spellings resolve to the same dish. A word
that fits several dishes equally ("chicken") brings all of them; a word in dish names
("pav") finds every dish with it (Pav Bhaji, Misal Pav, Vada Pav); dish types
("biryani") return every dish of that type. Results are ordered by confidence, and the
people with the highest trust for those dishes are listed as **people to trust**.

## The feed

* **Fresh**: dishes with a new photo in the last 14 days, newest first (the editorial
  order leads on launch day). Old dishes nobody endorsed don't stay in it forever.
* "Not for me" never adds feed points; undoing it also takes back the "tried" it added.

## Tuning

Every number above lives in the `ranking_config` table (`pp_*`, `trust_*`, `expert_*`,
`standing_*`, `twin_*`) and can be changed without shipping the app. The admin API
rejects values that would break the maths (and every value is clamped where it's used);
a change re-ranks everything at once. The SQL is in
`backend/migrations/007_fair_ranking.sql`: a vote re-rates its dish at once, and trust,
standing and lockstep partners are rebuilt a few seconds after a burst of votes and on
the ranking timer.

## People like you: Taste Match and For You

FoodFeed recommends through **people**, not just through the crowd.

**Your taste.** A short profile (dish types you love, veg / egg / non-veg, spice 1 to 5),
asked once after signing in and editable in Settings. Your votes teach the rest: per dish
type, a love counts +1, a save +0.5, a "not for me" −1, and each type you picked +1.

**Taste Match** (0 to 100 %, on every profile) blends three signals, each only when there's
something to go on:

| Signal | Score | Weight |
|---|---|---|
| Votes on the same dishes | (agreed + 1) / (shared + 2) | 3 × min(shared, 10) / 10 |
| Dish types you both like | cosine of the two taste vectors | 1.5 × min(1, the smaller voting record / 8) |
| What you both told us | ½ type overlap + 0.3 diet + 0.2 spice closeness | 1 |

Someone with less than one unit of weight gets no score yet. **People with your taste**
(Home) lists people at 50 %+ with full standing that you don't follow yet, surest first
(a close match on lots of evidence beats a perfect match on a little).

**For You** (Home and an Explore tab) ranks the dishes that your closest taste matches
(50 at most, 55 %+) and the people you follow loved:

```
score = Σ (their weight × their standing × fade over 90 days × (+1 love, +0.5 save, −1 not for me))
their weight = taste match (+0.25 if you also follow them), or 0.7 for someone you only follow
```

Dishes you've already loved, passed or tried are left out, a dish they mostly passed on is
left out, and vegetarians never see non-veg dishes. Each card says why ("Loved by @diya ·
92 % taste match"). With nobody to go on yet, the best-rated dishes of the types you like
fill in ("Because you like Thali").

**The map** can show only the places your friends (people you follow) or your taste matches
loved, with who loved each one. **Food on the way**: choose a city (or long-press the map)
and the server finds a driving route (an OSRM server, `ROUTING_URL`; a straight line if it
can't reach one), then the best dish at every place within 3 km of the road, in the order
you reach them. "Stop on the way" opens your maps app with that place as a stop.

The SQL is in `backend/migrations/008_taste.sql` (`taste_matches()`, `for_you()`).
