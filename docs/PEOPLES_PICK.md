# People's Pick: how WorthAbite decides what you should eat

The question WorthAbite answers is "I want pav bhaji, where should I go?". Star ratings
don't answer it: they rate a whole restaurant, and anyone can leave one. WorthAbite ranks
**one dish at one place** by **what people who actually tried it decided**, and it trusts
some people more than others because of their track record.

## Votes

| Vote | Meaning |
|---|---|
| **Loved it** (recommend) | "Go here for this." |
| **Not for me** (pass) | "I tried it and wouldn't send you there." Also marks it tried. |
| Tried, no verdict | Ignored by the rating, and adds no feed points. |
| Save, share | Feed points only (Trending, tie-breaks); never part of the rating. |
| **Posting a photo** | Asks "Worth a bite?": Loved it / Not for me, counted like the buttons. |

Loved it and Not for me exclude each other. Whoever added a place doesn't vote for its
dishes (their votes are kept, but count for nothing), so an owner can't push their menu.
Their votes there don't build their trust either (see below), and they aren't listed
among the people who loved those dishes.

A vote from someone who posted a photo of the dish with a **verified visit** counts
`visit_weight` (1.25) times: see "Genuine photos" below. That only adds weight to the
vote: being a champion or an expert still takes the standing on its own.

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
  For Top they are people who loved the dish or said "not for me" (`w_voters`, the owner
  left out): saves and shares alone never put a dish in Top.

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
* Votes at a place by whoever added it are not picks, and don't count as later voters
  agreeing with anyone else's: an owner can't build trust from their own menu.
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
  A champion you've blocked isn't shown to you. The faces on a card are the latest people
  who loved it (not people who only marked it tried, nor the owner, nor anyone you blocked).
* A place's page lists its dishes by confidence: a dish nobody has rated sits at the
  prior, below a loved one and above one people passed on. Explore's "Most loved" sorts
  places by the weighted loves (trust × standing) on their dishes still up, not by how many
  accounts tapped Loved it.

## Searching a craving

Type "paav bhaji", "pavbhaji" or "pav bhaji": spellings resolve to the same dish. A word
that fits several dishes equally ("chicken") brings all of them; a word in dish names
("pav") finds every dish with it (Pav Bhaji, Misal Pav, Vada Pav); dish types
("biryani") return every dish of that type. Results are ordered by confidence, and the
people with the highest trust for those dishes are listed as **people to trust**.

## The feed

* **Top** ranks by People's Pick; feed points (saves, shares) only break ties. A place's
  third dish and beyond move `place_spread` (10) cards down per extra dish, so no place
  has more than two dishes in any ten cards.
* **Nearby** is nearest first, People's Pick between equally near dishes.
* **Trending** still follows this week's feed points.
* **Following** (in the app): photos from the people you follow, newest first, with no
  time limit, so someone you've just followed shows up with their older posts too.
* **Fresh**: dishes with a new photo in the last 14 days, newest first (the editorial
  order leads on launch day). Old dishes nobody endorsed don't stay in it forever. Only
  photos from diners count: the place's own account re-posting doesn't keep its dishes
  at the top, and deleting a post doesn't give back the day's posting slots.
* "Not for me" never adds feed points; undoing it also takes back the "tried" it added.

## Tuning

Every number above lives in the `ranking_config` table (`pp_*`, `trust_*`, `expert_*`,
`standing_*`, `twin_*`) and can be changed without shipping the app. The admin API
rejects values that would break the maths (and every value is clamped where it's used);
a change re-ranks everything at once. The SQL is in
`backend/migrations/007_fair_ranking.sql`, `013_fair_feed_and_genuine_photos.sql` and
`015_ranking_fixes.sql`: a vote re-rates its dish at once (under a lock for that dish
only, so it never waits for a full rebuild), and trust, standing and lockstep partners
are rebuilt a few seconds after a burst of votes and on the ranking timer (a rebuild that
finds another one running leaves it to finish).

## People like you: Taste Match and For You

WorthAbite recommends through **people**, not just through the crowd.

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

## Genuine photos

A photo can't be proven real, but these make faking one costly and copying one useless:

* **Fingerprint.** Every upload gets a 64-bit difference hash (the photo shrunk to 9x8
  greys; each bit says whether a pixel is brighter than its neighbour). Resizing or
  recompressing barely changes it; a different photo differs in about half the bits.
  Within `copy_distance` (6) bits it's the same photo. The fingerprint is also kept as
  its 8 bytes (`ph_bands`, indexed): two photos 6 bits apart still share at least 2 of
  them, so only photos sharing one are compared bit by bit.
  * your own photo again is refused ("You've already posted this photo");
  * someone else's is **held** for an admin, shown side by side with the original
    (Allow if it's genuine, say friends at one table; Remove if it was copied). A held
    photo gets no credit and can't be a cover: its "Worth a bite?" answer isn't counted.
* **Verified visit.** The app sends where the phone is when posting, and how and when the
  photo was taken (camera now, or the gallery photo's own date). Within `visit_radius_m`
  (300 m) of the place, with a camera photo or one taken in the last `photo_fresh_days`
  (3) days, the post shows a **Verified visit** badge and its author's vote counts 1.25x.
  Both come from the phone, so a determined cheat can fake them (GPS spoofing): they're a
  strong hint, which reports and the other checks back up.
* **One photo of a dish per person per day**, so many photos of one plate are never many
  votes. Each person has one vote per dish however many photos they post. Uploads are
  counted (`post_uploads`), not posts, so deleting a photo doesn't free the slot, and the
  limit follows the dish the photo lands on (a merged-away name counts as its dish).
* **Did you mean…?** Posting "Butter Chkn" where "Butter Chicken" exists offers the
  existing dish. If the app posts the new name anyway and it's a near typo (trigram
  similarity ≥ 0.5 and at most one edit per six letters, e.g. "Buter Chiken"), the photo
  joins the existing dish and the reply carries its real name; "Paneer Tikka Masala" is
  still its own dish next to "Paneer Tikka". A name an admin merged away posts to the
  dish it became, and a dish an admin hid can't be brought back by posting it
  (`dish_unavailable`); adding a place with a similar name within 150 m offers the existing
  place. Admins see look-alike dish names at one place and can **merge** them: photos
  and votes move over, each person's latest love or "not for me" is their one vote, and
  old links open the merged dish.
* **Cover.** A dish's cover is its best recent photo (last `cover_days`, 90): a verified
  visit first, then the most trusted photographer, then the newest. Cards say how many
  people photographed it.

## Is it working?

The admin screen's **Algorithm health** card (`GET /admin/health`) shows votes per day,
how many dishes have 3+ votes, verified photos, own-place votes ignored, accounts voting
in lockstep, copies held and removed, dishes merged, how much each city's Top 20 changed
in a week (a snapshot is kept daily), and how often people open the cards each tab shows.

The **prediction check** takes every love or "not for me" from the last 30 days and predicts
it from only the votes cast on that dish **before** it, as if recommending the dish at that
moment: nothing from the future leaks in. "Skill" compares that with always guessing the
overall share of loves (above 0 means the ranking knows something, 1 would be perfect).
"Skill without trust" does the same with every voter counted equally, so the gap shows what
trust weighting adds. Watch both before and after changing a setting.

The backend tests include a **test village**: 20 honest diners rate a great, an okay and a
poor dish; a ring of 8 new accounts and the place's owner all love the poor one. The
great dish must beat the okay one, and the okay one must beat the spammed one.

## Keeping it fast

* Trust reads, for each vote, the standing of everyone who voted on the dish after it from
  running totals per dish (one pass), not by comparing every vote with every other.
* Each person's taste per dish type lives in `taste_affinity_cache`, rebuilt for that person
  by the database whenever their votes or stated taste change (and fully on the ranking
  timer), so Taste Match reads one indexed table instead of re-adding every vote.
* A vote re-rates only its dish; the full rebuild never makes a vote wait.
