# WorthAbite (working name): Idea, Research and Android MVP Plan

**Document date:** 15 October 2026
**Status:** Planning document. No code has been written yet.
**Audience:** You (founder and sole engineer) and, later, any friend, tester or co-builder you share it with.
**Working name:** "WorthAbite" is a placeholder taken from your project notes. Availability of the name on the Play Store, as a domain and as a trademark has **not** been checked.

### How to read this document

Every statement in this document falls into one of these groups, and I tag the ones that need care:

| Tag | Meaning |
|---|---|
| **[You]** | Something you told me directly during our conversation. |
| **[Research]** | A finding from the competitor research (report: `Dish discovery app competitor landscape.md`). Source quality is noted where it is weak. |
| **[Proposal]** | My suggestion. You have not decided it. Reasonable people could choose differently. |
| **[Assumption]** | A number or belief I am using because no evidence exists yet. Treat it as something to test. |
| **[Verify]** | A fact I believe is true but have not confirmed against current official sources. Check before relying on it. |

Where this plan departs from what you originally described, it says so in plain words (see section 4.6).

All restaurant, dish and person names in examples (for example "Cafe Ananda", "Riya", "Arjun") are invented for illustration. The city "Nashik" appears in a few examples only because it is a plausible launch city, not because you chose it.

---

## Table of contents

1. [The idea in detail](#1-the-idea-in-detail)
2. [Problem, users and why now](#2-problem-users-and-why-now)
3. [Research summary: who else is doing this](#3-research-summary-who-else-is-doing-this)
4. [What is unique about what we are building](#4-what-is-unique-about-what-we-are-building)
5. [Product scope: the Android MVP](#5-product-scope-the-android-mvp)
6. [The ranking system](#6-the-ranking-system)
7. [Content and community strategy](#7-content-and-community-strategy)
8. [Technical architecture and data model](#8-technical-architecture-and-data-model)
9. [Android app implementation details](#9-android-app-implementation-details)
10. [Legal, privacy and Play Store compliance](#10-legal-privacy-and-play-store-compliance)
11. [Build plan: week by week](#11-build-plan-week-by-week)
12. [Testing and release](#12-testing-and-release)
13. [Metrics, validation and kill criteria](#13-metrics-validation-and-kill-criteria)
14. [Risk register](#14-risk-register)
15. [Costs](#15-costs)
16. [Decisions you need to make](#16-decisions-you-need-to-make)
17. [After the MVP: roadmap](#17-after-the-mvp-roadmap)
18. [Appendices](#18-appendices)

---

## TL;DR

- **The idea:** an app where the unit is a *specific dish at a specific place* (for example "misal pav at Cafe X"), ranked inside your city by how many real people recommend, save and share it. The restaurant's overall star rating is not the ranking input. **[You]**
- **Is it new?** No. FavHiker (India), Foodspotting (US, shut down in 2018), Dish.fm (closed) and several small apps already do dish-first discovery. **[Research]** Swiggy and Google have added dish-level features too.
- **Is it still worth building?** Yes, as a narrow, local, trust-first product. I found no live Indian app that combines a fast vertical dish feed, ranking by community recommendation, inclusion of low-rated places, and deep coverage of one city. **[Research]** I also found no data proving Indian diners want this, so demand must be tested early.
- **Biggest changes from your original plan:** (1) do not scrape Instagram for the MVP, (2) use a vertical full-screen feed instead of right/left card swipes, (3) treat the ranking weights as a guess to be tested, (4) seed one city by hand. Reasons are in section 4.6.
- **Android MVP stack (proposed):** React Native with Expo and TypeScript, Supabase (Postgres, PostGIS, Auth, Storage) as the backend, PostHog and Sentry for measurement.
- **Realistic timeline:** about 206 focused hours for the must-have list (about 267 with the nice-to-haves). Full time, that is 5 to 6.5 weeks. At around 13 hours a week alongside a job and an MCA, it is 16 to 20 weeks. A "Lite" version (seed-only content, about 130 hours) gets testers a working app in about 3.5 full-time weeks or 10 part-time weeks. My earlier "4 weeks" only holds if you work close to full time and cut scope. Section 11 gives the schedules.
- **Before writing code:** spend one week testing demand with a "dish of the week" Instagram or WhatsApp channel for your city and 15 to 20 short interviews (section 13).

---

## 1. The idea in detail

### 1.1 One sentence

> A local food app that shows you the specific dishes people in your city actually recommend, ranked by how many of them recommend it, regardless of how the restaurant is rated overall.

### 1.2 The motive story (your words, organised)

**[You]** The starting point is a real social behaviour. A friend tries a new restaurant or cafe. They loved one particular item. They tell you: "Go there, order *that*." They do not tell you the restaurant's star rating, and they do not care what it is. They care about the dish. The recommendation works because you trust the person.

Existing apps do not capture this. They rank restaurants. A cafe with 3.1 stars may serve the best filter coffee in the city, and a 4.6-star restaurant may have three great dishes and ten forgettable ones. The app you want to build turns that friend-to-friend recommendation into a feed for a whole city: "These are the dishes people near you are recommending right now."

**[You]** The recommendation count is the main signal. Ratings exist, but they are a secondary feature.

### 1.3 Core principles

These are the rules the product should follow when two options conflict. **[Proposal]** (derived from what you said).

1. **The dish is the unit, not the restaurant.** Every card in the feed is a dish. The restaurant is attached information.
2. **People, not stars.** The main number on a card is "recommended by N people", not a rating.
3. **No quality gate by restaurant rating.** A place is eligible whatever its overall rating. (FavHiker does the opposite, see section 3.)
4. **Local first.** The feed shows your city, and later your neighbourhood. A great dish in another city is not useful to you today.
5. **Fast and light.** It must feel like Instagram: instant image, smooth scroll, one tap to save. **[You]**
6. **Honest content.** Sponsored or comped posts are labelled. A feed that hides paid recommendations loses the one thing it sells, which is trust.
7. **Small and finished beats big and half-built.** The MVP does a few things well.

### 1.4 What the app is and is not

| It is | It is not |
|---|---|
| A feed of dishes in your city, ranked by community recommendation | A restaurant directory or review site |
| A place to save dishes you want to try | A food delivery or ordering app |
| A way to recommend a dish with one tap and a photo | A long-form review platform |
| Local and hyperlocal | A global food encyclopedia |
| Free for diners | A discount or deals marketplace (for now) |

### 1.5 Glossary (terms used in this document)

| Term | Meaning |
|---|---|
| **Dish** (or **dish listing**) | One dish at one restaurant, for example "Masala dosa at Cafe Ananda". This is the ranked object. A restaurant can have many dishes. |
| **Post** | A photo (with an optional caption) of a dish, uploaded by a person. A dish can have many posts. The best photo becomes its cover. |
| **Recommend** | One-tap "I recommend this dish" (your "like/upvote"). **[You]** |
| **Save** | Bookmark a dish to try later. **[You]** |
| **Share** | Send a dish to someone through the Android share sheet. **[You]** |
| **Tried it** | A stronger signal: "I ate this." **[Proposal]** (see 5.2) |
| **Locality** | A neighbourhood inside a city, for example "College Road". |
| **Hidden gem** | A dish with strong community support at a place with low visibility. Defined in section 6.5. **[Proposal]** |
| **Seed content** | Posts you or a small team add by hand before launch so the feed is not empty. |
| **Cold start** | The problem that a new feed has no content, so nobody returns, so no content arrives. |
| **Wilson lower bound** | A statistical way to rank items with few votes without letting a 2-vote item beat a 40-vote item. Explained in section 6. |

### 1.6 Decisions you have already made

| Topic | Your answer |
|---|---|
| Who posts content | Both Instagram-sourced content and local users' own posts **[You]** |
| How people recommend | Like/upvote, share and save, all three combined **[You]** |
| Discovery modes | Browse by food type (for example biryani, then restaurants) and browse by restaurant, both **[You]** |
| Location | Auto-detect city and locality on launch, like Swiggy. One city first. **[You]** |
| Ranking basis | Community recommendation count, not star ratings. Ratings are a secondary feature. **[You]** |
| Interaction style | Right/left swipe on food cards **[You]** (see 4.6 for a recommended change) |
| Tech stack | No preference, but it must feel snappy and smooth like Instagram **[You]** |
| Platform for now | A small Android app **[You]** |
| Process | Research and plan first, no implementation until the plan is agreed **[You]** |

### 1.7 Three short scenarios

**Scenario A: the evening decision.** Riya opens the app at 7 pm. It shows the top dishes near her. A photo of a thick cold coffee at a small cafe 1.2 km away says "Recommended by 38 people". She saves it, taps directions, and goes.

**Scenario B: the post.** Arjun tries a bhurji pav at a tiny stall. He opens the app, takes one photo, types the dish name (the app suggests matches), confirms the place on a short list of nearby places, and posts in under 30 seconds. His post joins the dish's page. Over the next week, 9 people recommend it. The dish climbs the "Hidden gems" list.

**Scenario C: the visitor.** Priya's cousin visits for the weekend and asks "what should I eat here?". Priya shares a link to three saved dishes. The cousin opens it in a browser, sees the photos and places, and can install the app.

---

## 2. Problem, users and why now

### 2.1 The problem

Food discovery apps answer "which restaurants are rated highly?". People often want to answer "what should I order, and where?". The gap shows up in three ways:

1. **Averages hide the best item.** A restaurant's overall rating mixes every dish, service and price. One great dish at a mediocre place disappears. Foodspotting's founder described exactly this: even a "one-star restaurant can have one amazing dish". **[Research]**
2. **Trust is thin.** Ratings can be gamed, and the Indian regulator has published guidance on fake reviews. Small city food pages are often paid or comped (barter is common at small-creator level, with disclosure rules from ASCI). **[Research]** There is no independent survey in my research measuring how much Indian diners distrust ratings, so this belief is **[Assumption]**.
3. **Discovery is moving to social feeds.** Google's own senior vice president said almost 40% of young people looking for lunch go to TikTok or Instagram instead of Google Maps or Search (reported 2022, an internal Google statement). **[Research]** That suggests people already want a visual, social way to find food.

### 2.2 Who it is for

**Primary persona: "The Curious Local" (aged 18 to 35, urban or semi-urban India)**
- Eats out or orders in 2 to 4 times a week.
- Already follows food pages on Instagram.
- Annoyed when a highly rated place is disappointing.
- Likes finding a place before friends do.
- Has a mid-range Android phone and unreliable mobile data some of the time.

**Secondary persona: "The Local Food Person" (creator, blogger or enthusiast)**
- Posts food photos regularly.
- Wants credit and followers for recommendations.
- Might post to this app if posting is quick and credit is visible.

**Tertiary persona: "The Small Place Owner"**
- Runs a cafe or stall with one or two standout dishes.
- Has little marketing budget.
- Would like to be found for the dish that makes the place special.
- Not a launch audience, a later one. Restaurants should not be able to buy ranking.

### 2.3 Jobs to be done

| When I... | I want to... | So I can... |
|---|---|---|
| am deciding where to eat tonight | see what people near me recommend | pick quickly without reading 40 reviews |
| try something great | tell people about *that dish* in 30 seconds | help friends and get credit |
| hear about a dish from a friend | save it | remember it when I am nearby |
| have visitors in town | share a short list of local must-orders | look like I know the city |

### 2.4 Anti-goals

Things we deliberately will not try to be, at least for now:

- A review aggregator with star averages as the headline.
- A delivery platform, a booking platform or a discounts app.
- A scraper of other people's Instagram content (see section 7.4).
- A feed that anyone can pay to rank higher.

---

## 3. Research summary: who else is doing this

> **Method and limits.** This section condenses the deep-research report. Seven researchers made roughly 300 tool calls (searches and page reads) in two rounds, and I spot-checked key claims against their notes. It is a desk study from public pages. I did not install or use the competitor apps. Competitor internals are mostly not public. Where a number is self-reported or comes from a single source, it is marked.

### 3.1 The short answer

The idea exists in several forms. **No funded, scaled Indian product combines a fast vertical dish feed, community-recommendation ranking, no rating gate and deep single-city coverage.** The closest Indian product, FavHiker, is small and its growth looks stalled. **[Research]**

### 3.2 Landscape table

| Product | Region | Status | Dish-first? | How it ranks or finds dishes | Notes |
|---|---|---|---|---|---|
| **FavHiker** | India (Mumbai company Delnie Pvt Ltd) | Live | Yes | Community "GoldenBite" awards, "AI Picks" from public reviews, listings pre-filtered to restaurants rated 4.5+ with 1,000+ reviews | About 64k Android downloads since March 2024, about 300 in the last 30 days (AppBrain, third-party tracker). No funding found. Own claims of scale conflict (12.8k restaurants on one page, 143.5k on another). App Store: 4.4 from 41 ratings (India), 3.0 from 2 (US). Top complaint: forced signup, no guest mode. Premium: Rs 499 a year. |
| **Foodspotting** | US | Shut down May 2018 | Yes | Users photographed dishes and tapped "nom" (later "Tried it / Loved it / Want it") | Launched 2009. About 4M downloads by Jan 2013. Raised about $3.75M. Sold to OpenTable for $10M cash in Jan 2013. No clear revenue model. |
| **Dish.fm** | Moscow and US | Closed | Yes | Mined Yelp, Foursquare and Instagram with sentiment analysis. Each positive mention counted as a "like". | About $300k seed. Pivoted from crowdsourcing because "there's not enough content there". |
| **Beli** | US | Live and growing | Partly (restaurant ranking, favourite dishes) | You rank places by pairwise comparison. A personal score and a friends' score. | Reported 58M to 75M ratings (sources differ). Funding figures conflict ($6.15M on PitchBook vs $12M on Wikipedia, unverified). About 80% growth from referrals. Tiny team (4 to 8 depending on source). Revenue: an invite-only Supper Club subscription ($74.99 a year in the App Store). |
| **Swiggy Eatlists** | India | Launched 5 July 2024; current status unconfirmed | Yes (dish lists) | Users bookmark dishes into shareable lists | Inside a delivery app. No published usage data. |
| **Swiggy Dineout "Bites"** | India (Delhi NCR, Mumbai, Bengaluru) | Live since Dec 2025 (reported) | Partly | Short video feed with restaurant, rating, price and "Book Now" | Probably a paid-visibility surface for restaurants. No usage data. |
| **Google Maps "popular dishes"** | Global (India availability unconfirmed) | Live since 2019 | Partly | Machine learning over review text and photos | Needs many reviews. |
| **Yelp "popular dishes"** | US and others | Live | Partly | Neural tagger finds menu items in reviews; first version ranked by raw mention count | Best-documented engineering of any competitor. |
| **Mapie** | India | Early web preview | Partly | Friend and taste matching ("food twins") | Single creator, no traction numbers. |
| **Dishy, DishRank, Savor, TasteRate** | US, France and others | Live, tiny | Yes | Mostly user ratings of dishes | Dishy shows "10+" downloads. DishRank is mostly Lyon. |
| **Dishcision** | Unclear | **Unverified** | Claimed | Claimed mix of ratings, menu prominence and order frequency | Appears in only one blog written by its own team. Ignore until confirmed. |
| **Tinder-style food apps** (Nibbly, Tender, FoodMe, Feed Me, others) | US, 2015 to 2017 | None became a category leader | No (restaurant or recipe) | Swipe cards | No retention or shutdown data found. |
| **Instagram, TikTok** | Global | Live | No | Algorithmic feeds | The real attention competitors for food discovery. |

### 3.3 What each important player teaches us

**FavHiker (the closest live competitor).**
- It proves a small Indian team can ship a dish-first app, and that there is some demand (tens of thousands of installs).
- It also shows the limits: slow growth, no guest mode (people leave at the signup wall), and a restaurant base seeded from external, highly-rated listings. That choice contradicts the "hidden gem at a low-rated place" idea.
- It claims 2,000+ Indian "cities" for a 64k-download app, which suggests mostly empty auto-generated city pages. That is my inference, not a finding. Depth in one city appears to be an open lane.
- It is free for restaurants to claim a listing and charges diners (Premium). Its stated stance is "paid by diners, never by restaurants".

**Foodspotting (the original).**
- Same insight as yours, 2009. It grew to millions of downloads, so people did try it.
- It never found revenue. Its CEO said in 2011 she did not know how it would make money. A 2012 pilot paid users cash for photos in San Francisco, with restaurants paying for featured dishes only as a future plan, and no results were published.
- After OpenTable bought it, the iOS app went un-updated from early 2014 and the service shut in 2018. The exact reason was never stated.
- Lesson: a dish app can attract users and still die without a revenue plan or a committed owner.

**Dish.fm (the shortcut that failed).**
- It skipped the cold start by mining Yelp, Foursquare and Instagram. That gave instant content, but it depended on other platforms and had no community of its own. It closed with no stated cause.
- Its co-founder explained the cold-start trap: users wanted around ten dishes per restaurant with around ten votes each before they would trust it.

**Beli (the one that is winning).**
- Trust comes from friends, not strangers.
- Growth came from referrals (about 80%), and it stayed tiny and cheap.
- Its ranking is a game: you compare places against ones you have visited. It also attracted criticism for favouring elite restaurants and for streaks mattering more than real dining.
- Lesson: social loops and low burn beat big marketing.

**Swiggy (the incumbent in India).**
- Its own figures (company-reported): 58% of users struggle to decide what to eat and 68% rely on peer recommendations. That supports your thesis, but it is Swiggy marketing.
- Eatlists sits inside a delivery app, so it likely covers deliverable dishes. Dine-in cafes, stalls and street food are a gap. That is my reasoning, not a finding.
- Swiggy has shut down earlier experiments (Genie, Minis, Pyng), so Eatlists' quiet profile may signal low traction. That is unverified.

**Google and Yelp (the benchmark for dish ranking).**
- Google uses machine learning on reviews and photos. It prompts people who upload photos to name the dish, which also trains its model.
- Yelp's engineering blog describes a neural tagger that finds menu items in reviews, trained by matching known menus to reviews, with language rules merging "mac and cheese" and "macaroni & cheese". Its first Popular Dishes ranked purely by mention count.
- Lesson: raw counts are a start, but they need a quality guard against small samples and spam.

### 3.4 Why the earlier apps failed

**[Research]** (patterns across Foodspotting, Dish.fm, Forkly, Punchfork, Ness, Gojee, Urbanspoon, Burrp). The causes below are my synthesis, with the Forkly quote being the only founder statement.

1. **Content supply.** Not enough dishes with enough votes to be trusted (Dish.fm's own words).
2. **No revenue plan.** Forkly's co-founder: "it's not really being monetized at the moment". Foodspotting's founder was unsure.
3. **Dependence on others.** Dish.fm depended on Yelp, Foursquare and Instagram.
4. **Acquisition then neglect.** Foodspotting, Punchfork, Ness, Gojee and Urbanspoon were acquired, then folded or shut.
5. **Charging the wrong side.** Burrp in India charged restaurants for listings while Zomato listed them free (YourStory's analysis). It was sold in 2009 for Rs 4.5 crore and again in 2017 for Rs 6.7 lakh.
6. **Tiny teams without distribution.** Dish.fm had 1 to 10 people. Dishy has almost no installs.

### 3.5 What the survivors did differently

- **Beli:** friends-based trust, referral growth, tiny team, small paid tiers.
- **Burpple:** raised about $7.25M, stayed focused on Singapore and Kuala Lumpur, and found revenue in a 1-for-1 deals membership ($9.90 a month, $69 a year).
- **Zomato early:** it listed restaurants free to gain coverage, then made money on advertising and commissions.

None of these routes is available inside a 4 to 13 week MVP. The practical lesson is to **keep the cost low and stay alive long enough to learn.**

### 3.6 How competitors build and rank (what is known)

**Most internals are not public.** This is what is documented:

| Question | Finding | Evidence quality |
|---|---|---|
| Beli's stack | One undated job post lists Angular, Django and PostgreSQL with iOS and Android apps; the jobs page mentions GCP backend infrastructure. Native vs cross-platform unknown. | Job posts only |
| Beli's ranking | Described as: bucket as bad, okay or great; compare against places you have ranked; item is binary-searched into your list; score interpolated to 0 to 10. | A third-party article, not Beli |
| FavHiker's stack | Page metadata suggests Next.js on the web and an AWS S3 bucket in Mumbai. AI Picks and GoldenBite methods are not disclosed. | Indirect |
| Foodspotting's stack | Public forks on GitHub (geokit, Thinking Sphinx, Sidekiq, iOS libraries) suggest Rails on AWS. | Guess from forks |
| Dish.fm | Sentiment analysis over Yelp, Foursquare and Instagram; photos ranked by source likes and poster follower count. | TechCrunch 2012 |
| Google | One sentence: machine learning matches user-provided dish names with photos and reviews. | Google blog |
| Yelp | Hierarchical GRU with a CRF layer for dish tagging, daily PySpark batches, aliases merged by language rules; a 2026 post describes on-device menu OCR and matching with Cassandra storage. | Yelp Engineering blog |

**Takeaway:** nobody's recipe is available to copy. We design our own ranking (section 6) from published statistical methods.

### 3.7 What the research says about swipe and feed design

- The 2015 to 2017 "Tinder for food" apps (Nibbly, Tender, Foodie, FoodMe, Feed Me) left no survivors that I could find. A VICE critique argued that swiping rewards photogenic food over good food (an opinion, not data). **[Research]**
- The formats that reached scale are **vertical, full-screen paging**: Uber Eats launched a TikTok-like feed in April 2024 and said early data shows people are "much more confident trying new dishes" (no numbers), and Swiggy followed with Bites in December 2025. **[Research]**
- A study of news reading on tablets found that interaction signals (like swipes) predict engagement better than time-on-page for briefly viewed items. It is not about food, so it only suggests that a save or skip is a useful signal. **[Research]**
- Performance guidance from Shopify (FlashList) and Expo (image caching, prefetch, placeholders) is the practical route to a smooth feed in React Native. Benchmarks are vendor-reported. **[Research]**
- Location permission: a vendor whitepaper (undated, not food-specific) puts opt-in around 85% for delivery apps, higher with a clear explanation. **[Research]**

### 3.8 Instagram and the law (summary)

Full detail is in section 7.4 and section 10. In brief **[Research]** (not legal advice):

- The official Instagram API does not support discovering other people's food posts by place. Hashtag search is capped at 30 unique hashtags per rolling 7 days and needs App Review. Basic Display has reportedly been shut down.
- Scraping is possible technically but Instagram fights it. The one major US court ruling (Meta v. Bright Data, January 2024) was narrow: logged-out scraping of public data did not breach Meta's terms. It does not cover copyright or privacy and does not bind India.
- India's data protection law (DPDP Act, rules notified November 2025) has main duties starting around mid-May 2027 (sources differ between 13 and 14 May). The exemption for "publicly available" data is narrow, and India's IT minister said in August 2024 that scraping public user data falls under the Act.
- India's 2026 intermediary rules cut takedown timelines to as little as 3 hours on a court or government order.

### 3.9 What the research could not answer

- Whether Indian diners actually want this enough to return (no India-wide survey found).
- FavHiker's real Play Store rating, per-city content density and reviews (page blocked).
- Whether Swiggy Eatlists is still live and used.
- Whether Google's "popular dishes" works well in your city.
- Meta's current platform terms text and the DPDP statute text (secondary sources only).
- Competitor tech stacks beyond hints.
- How Indian creators would react to a dish app.

These are turned into concrete checks in section 13.

---

## 4. What is unique about what we are building

### 4.1 Honest starting point

"Dish instead of restaurant" is **not** unique. FavHiker, Foodspotting, Dish.fm, Dishy and DishRank each do a version of it, and Swiggy and Google have dish features. Anything we say about novelty must rest on the *combination* and the *execution*, and these must be proven with users.

### 4.2 Positioning statement **[Proposal]**

> For people in [city] who want to know what to order and not just where to go, WorthAbite is a fast, local dish feed ranked by how many real people recommend each dish. Unlike rating apps, it does not hide a great dish behind a restaurant's average score, and unlike social feeds, it is organised around food and place and labels anything paid.

### 4.3 The differentiators

Each differentiator below lists why it matters, the evidence, how we build it and how easily a competitor could copy it.

#### D1. Ranking by community recommendation with no rating gate

- **What:** dishes rank by how many distinct people recommend, save or share them (with a small-sample guard). A dish at a 2.9-star cafe can reach the top.
- **Why it matters:** this is your original insight. It is also the opposite of FavHiker's choice to list only restaurants rated 4.5+ with 1,000+ reviews. **[Research]**
- **How we build it:** section 6 (Wilson lower bound, impressions, decay).
- **Copy risk:** low effort for a funded competitor. Our edge must come from local data and community, not from the formula.

#### D2. Hyperlocal depth in one city before any breadth

- **What:** launch in one city with enough dishes per neighbourhood that the feed is useful on day one, then expand city by city.
- **Why it matters:** the research shows breadth without depth (FavHiker's thousands of thin city pages, Dish.fm's 865 cities) did not retain users. **[Research]** Depth is a **[Proposal]** based on that reading.
- **How we build it:** hand-seeding (section 7.1), a local dish vocabulary (D5), a city-first onboarding.
- **Copy risk:** a competitor cannot copy local depth quickly. It must be rebuilt city by city.

#### D3. Trust through visible people, not stars

- **What:** each dish shows "Recommended by N people" and the faces and handles of recent recommenders. Posts from sponsored, comped or restaurant-supplied sources are labelled.
- **Why it matters:** friends' trust is why Beli grows. Small creators often take barter deals that ASCI says must be disclosed, so an honest, labelled feed is a visible difference. **[Research]** The idea that this is a wedge is **[Assumption]**.
- **How we build it:** `is_sponsored` flag on posts, "N recommenders" count, recommender avatars, no paid ranking.
- **Copy risk:** easy to copy as a feature, hard to copy as a reputation.

#### D4. A fast, vertical, photo-first feed

- **What:** full-screen dish cards, one-tap save and recommend, prefetched images, a guest mode with no signup wall.
- **Why it matters:** the formats that scaled are vertical, and FavHiker's most-cited complaint is forced signup. **[Research]**
- **How we build it:** section 9.
- **Copy risk:** easy to copy. Good execution is an advantage only while we are small and move fast.

#### D5. A local dish vocabulary

- **What:** a dish taxonomy and alias table that knows "vada pav", "wada pav" and "vadapav" are the same, and that "misal" has regional styles. Search works with Hinglish spellings.
- **Why it matters:** Yelp's documented approach is alias merging and fuzzy matching. Indian dish names have many spellings, so general-purpose apps handle them poorly. That claim about general apps is **[Assumption]**.
- **How we build it:** `dish_categories` and `dish_aliases` tables, trigram search (section 8).
- **Copy risk:** moderate. It takes real local effort.

#### D6. "Hidden gems" as a first-class lane

- **What:** a tab that shows dishes with strong community support at places with low visibility (few posts, low overall footprint).
- **Why it matters:** it is the clearest expression of your idea, and it is something a restaurant-rating app structurally cannot offer.
- **How we build it:** section 6.5.
- **Copy risk:** low effort technically. It only works with enough local data.

### 4.4 Comparison matrix

| Capability | WorthAbite (planned) | FavHiker | Foodspotting (historic) | Beli | Swiggy Eatlists | Google Maps | Instagram |
|---|---|---|---|---|---|---|---|
| Dish is the main unit | Yes | Yes | Yes | No (restaurant) | Yes (lists) | Partly | No |
| Ranked by community recommendation count | Yes | Partly (awards, filters) | Partly | No (personal and friend scores) | No | No (reviews and ML) | No |
| Includes low-rated places by design | Yes | No (4.5+ filter) | Yes | Not a goal | Not a goal | Yes | Yes |
| Vertical photo feed | Yes | Not evident | No | No | No | No | Yes |
| Guest browsing without signup | Yes | No | Not known | Not known | Inside Swiggy app | Yes | Partly |
| Single-city depth | Goal | No | No | Partly (US cities) | Not city-specific | Yes (coverage, not food) | No |
| Labels sponsored posts | Yes (planned) | Not known | Planned only | Not known | Not known | Not known | Via platform tools |
| Dine-in and street food | Yes | Yes | Yes | Yes | Delivery-focused | Yes | Yes |

"Not known" means I could not verify it, not that it is absent.

### 4.5 How defensible is this?

Honestly, **not very, on technology alone.** The formula, the feed and the UI can all be copied. What is hard to copy:

1. **Local content and relationships** in a specific city.
2. **A reputation for honesty**, built over time.
3. **A community habit**, for example "I check WorthAbite before I order."
4. **Speed of iteration** by a focused small team.

So the plan puts the effort in seeding, community and measurement, not in clever technology.

### 4.6 What changed from your original brief

This is where the plan departs from your words **[You]**. Each change is a recommendation. You decide.

| Topic | Your original idea | This plan | Why |
|---|---|---|---|
| Content source | Scrape Instagram posts and comments for your city | **No scraping in the MVP.** Seed by hand and take user posts. Link to Instagram posts instead of copying them. | Official API cannot do geo discovery; scraping is technically fragile and legally unsettled; India's DPDP duties begin in 2027; copyright rests with creators. Dish.fm's reliance on other platforms is part of why it failed. **[Research]** |
| Interaction | Right/left swipe on cards | **Vertical full-screen feed** with tap actions. Horizontal swipe as an optional later experiment. | Card-swipe food apps from 2015 to 2017 left no survivors; scaled products use vertical paging; swiping left measures photo appeal more than dish quality. **[Research]** |
| Ranking weights | Like, share and save all count | **All three count**, but the specific weights (1, 1.5, 2.5) are an untested guess. A small-sample guard is added. | No source validates "share is the strongest signal". Raw counts distort with thin data (Yelp's first version). **[Research]** |
| Success targets | My first brief (not yours) suggested 1,000 installs and 200 daily users in 4 weeks | **Smaller, behaviour-based targets** (section 13) | 200 daily users from 1,000 installs is a 20% daily rate, which is a stretch. FavHiker took 2.5 years to reach about 64k installs. **[Research]** |
| Stack | No preference | React Native with Expo, plus Supabase | Ranking and geo queries suit SQL better than Firestore; Android-only now but a path to iOS later. **[Proposal]** |

Your core idea is unchanged: dishes, community-ranked, local, fast.

---

## 5. Product scope: the Android MVP

### 5.1 Scope principle

The MVP exists to answer one question: **do people in one city come back to a feed of recommended dishes, and do they recommend dishes themselves?** Everything in scope serves that question. Everything else waits.

### 5.2 Feature list (MoSCoW)

**Must have (the app is not usable without these)**

| ID | Feature | Notes |
|---|---|---|
| M1 | **City selection with auto-detect** | Ask for approximate location with a clear reason. If denied, show a city picker. One live city at launch. **[You]** |
| M2 | **Dish feed** | Full-screen vertical cards, ranked per section 6. Each card: photo, dish name, restaurant, locality, distance, "Recommended by N people", price (if known), veg/non-veg marker. |
| M3 | **Recommend, Save, Share** | One-tap recommend and save. Share opens the Android share sheet. **[You]** |
| M4 | **Browse by food type** | Category chips (biryani, misal, dosa, coffee, desserts and so on). Tapping a chip filters the feed. **[You]** |
| M5 | **Browse by restaurant** | Restaurant page lists its dishes, ranked. **[You]** |
| M6 | **Dish detail page** | Photo carousel, restaurant info, map pin, "Open in Maps" button, recommender count and avatars, report button. |
| M7 | **Guest browsing** | The whole feed works without an account. Account is requested on the first recommend, save or post. **[Proposal]** (FavHiker's forced signup is its most-cited complaint). |
| M8 | **Sign in** | Google Sign-In. Phone OTP can wait (SMS costs). |
| M9 | **Saved list** | All dishes the user saved, with distance. |
| M10 | **Add a dish (post)** | Photo from camera or gallery, pick or add restaurant, pick or type dish name, optional caption, optional price. Target under 30 seconds. |
| M11 | **Report and block** | Report a post, dish or user. Required for apps with user-generated content. |
| M12 | **Seed tooling** | A simple way for you to bulk-add restaurants, dishes and photos (spreadsheet import plus admin screen). Not user-facing but essential. |
| M13 | **Account deletion** | In-app deletion flow plus a web page link. Play Store requirement for apps with accounts. **[Verify]** |
| M14 | **Analytics and crash reporting** | Needed to learn anything from the pilot. |

**Should have (important, but the app works without them)**

| ID | Feature | Notes |
|---|---|---|
| S1 | **"Tried it" button** | A stronger, verifiable-in-spirit signal: "I ate this." Counted separately and shown as "tried by N". **[Proposal]** |
| S2 | **Hidden gems tab** | See section 6.5. |
| S3 | **Trending tab (last 7 days)** | Cheap to add once the ranking function exists. |
| S4 | **Search** | Dish and restaurant search with alias and typo tolerance. |
| S5 | **Restaurant rating (secondary)** | 1 to 5 stars, shown on the restaurant page only, never as the headline. **[You]** (secondary) |
| S6 | **Share links with a web preview** | A link opens a simple web page with the photo and an "Open in app" button. Needs a small domain. |
| S7 | **Sponsored/comped label** | A required toggle on creator and restaurant-supplied posts. Section 7.3. |
| S8 | **Profile page** | Handle, avatar, posts, recommendations count. |
| S9 | **Follow a person** | Followed people's recommendations rank slightly higher for you. This is the first step toward friend-style trust. |

**Could have (only if time remains)**

| ID | Feature |
|---|---|
| C1 | Horizontal swipe-to-save experiment behind a feature flag |
| C2 | Push notifications ("a dish you saved is near you") |
| C3 | "Paste an Instagram link" attachment on a post (stores the link only) |
| C4 | Hindi/Marathi UI strings |
| C5 | Dish lists (like Swiggy Eatlists) that users can name and share |
| C6 | Dark mode |
| C7 | Distance/price/veg filters |

**Won't have in the MVP (explicitly out)**

- Scraping or auto-importing Instagram content
- iOS app (design for it, do not build it)
- Ordering, delivery, booking, payments, coupons
- Restaurant dashboards and paid promotion
- Direct messages and comments threads
- Video posts
- AI taste matching and recommendation models
- Multiple live cities

### 5.3 User stories with acceptance criteria

Each story lists what "done" means so you can test it yourself.

**US1. First launch**
*As a new user, I want to see good dishes immediately, so that I understand the app in 10 seconds.*
- The first screen after the splash is a city confirmation sheet ("Show dishes near you in Nashik?") with "Yes" and "Choose another city".
- Without any signup, the feed shows at least 20 dishes within 3 seconds on a mid-range phone on 4G. **[Assumption]** target.
- If location permission is denied, the city picker appears and the app still works.

**US2. Browse the feed**
- Swiping up goes to the next dish card; the next card's image is already loaded.
- The card shows the dish name, restaurant, locality, distance, recommender count and three buttons: Recommend, Save, Share.
- Pulling down refreshes. Reaching the end loads more.

**US3. Recommend or save**
- A guest who taps Recommend or Save sees a bottom sheet "Sign in to save this" with a Google button. After signing in, the action is completed automatically, with no second tap.
- The count updates instantly (optimistic) and rolls back with a message if the server rejects it.
- Tapping again removes the action.

**US4. Filter by food type**
- A horizontal chip row at the top lists categories. Selecting one reloads the feed for that category within the city.
- "All" returns to the main feed.

**US5. Restaurant page**
- Tapping the restaurant name opens a page with name, locality, map pin, "Open in Maps", and its dishes ranked by score.

**US6. Add a dish**
- The "+" button opens the camera or gallery. After a photo is picked, the user types a dish name (suggestions from the alias table appear) and picks the restaurant from nearby places or adds a new one (name plus map pin).
- Submitting shows the post at once on the user's own profile. New restaurants are marked "pending" until reviewed. A new dish at an existing restaurant goes live at once.
- The uploaded image is resized on the phone before upload (section 8.5).
- Posting is rate-limited (for example 10 a day per user). **[Assumption]**

**US7. Report**
- Every card and detail page has a "Report" action with reasons (wrong place, not food, inappropriate, spam, other). Three reports from distinct users hide the post pending review. **[Assumption]**

**US8. Delete account**
- Settings has "Delete my account". Confirmation removes personal profile data and marks the user's posts as removed or anonymous per the privacy policy.

### 5.4 Screen inventory

| # | Screen | Purpose |
|---|---|---|
| 1 | Splash | Logo, session restore |
| 2 | City sheet | Confirm or choose city |
| 3 | **Feed** (home) | The vertical dish feed with category chips and tabs (Top, Trending, Hidden gems, Nearby) |
| 4 | Dish detail | Carousel, restaurant, recommenders, actions |
| 5 | Restaurant page | Info and dishes |
| 6 | Search | Search dishes and restaurants |
| 7 | Saved | Saved dishes |
| 8 | Add dish | Photo, dish, restaurant, caption |
| 9 | Pick/add restaurant | Nearby list or create |
| 10 | Profile | Own and others' |
| 11 | Settings | City, account, privacy, delete, about, legal |
| 12 | Sign-in sheet | Google button |
| 13 | Report sheet | Reasons |
| 14 | Empty/error states | Offline, no dishes in this category, location denied |

### 5.5 Wireframes (text)

**Feed screen**

```
┌───────────────────────────────┐
│ Nashik ▾          🔍          │  <- city, search
│ [Top][Trending][Gems][Near]   │  <- tabs
│ (All)(Misal)(Coffee)(Biryani) │  <- category chips
├───────────────────────────────┤
│                               │
│                               │
│        FULL-SCREEN            │
│         DISH PHOTO            │
│                               │
│                               │
│ ───────────────────────────── │
│ Sabudana Khichdi        🥬    │
│ Cafe Ananda · College Rd 1.2km│
│ Recommended by 38 people      │
│ (A)(B)(C) +35                 │
│                               │
│   [♥ Recommend] [🔖 Save] [↗] │
└───────────────────────────────┘
```

**Dish detail**

```
┌───────────────────────────────┐
│ ←                          ⋮  │
│  [ photo carousel  1/4 ]      │
│  Sabudana Khichdi   ₹80  🥬   │
│  Cafe Ananda, College Rd      │
│  Recommended by 38 · Tried 21 │
│  (A)(B)(C)(D)                 │
│  [Recommend][Save][Share]     │
│  [I tried this]               │
│  ─────────────────────────    │
│  📍 map pin     [Open in Maps]│
│  More at this place           │
│  [dish][dish][dish]           │
│  Sponsored? label shown here  │
│  Report                       │
└───────────────────────────────┘
```

**Add dish**

```
┌───────────────────────────────┐
│ ← Add a dish           [Post] │
│ [  photo preview           ]  │
│ Dish name  [ sabudana khi…  ] │
│   suggestions: Sabudana       │
│   Khichdi · Sabudana Vada     │
│ Place      [ Cafe Ananda ▾  ] │
│   nearby: Cafe Ananda · …     │
│   + Add a new place           │
│ Price (optional)  [ ₹ 80 ]    │
│ Caption (optional)            │
│ ☐ This post is sponsored or   │
│   I received this for free    │
└───────────────────────────────┘
```

### 5.6 Core user flows

1. **Discover and save:** open app, confirm city, scroll feed, tap Save, sign-in sheet (first time only), saved.
2. **Post a dish:** tap +, pick photo, dish name, place, post.
3. **Plan a visit:** open Saved, tap dish, tap Open in Maps.
4. **Share:** tap Share, Android share sheet, choose WhatsApp, recipient gets text plus link.
5. **Report:** tap ⋮, Report, choose reason, confirmation.

---

## 6. The ranking system

### 6.1 What the ranking must do

1. Reflect **how many people recommend a dish** (your core signal). **[You]**
2. Count a person once, so one fan cannot flood the score.
3. Let new dishes get seen, otherwise popular dishes stay popular forever.
4. Slowly forget old buzz, so the feed stays current.
5. Be explainable on a single screen, so a restaurant owner or a tester can understand why a dish is where it is.

### 6.2 Two numbers on every dish

- **Public number:** "Recommended by N people" is the count of **distinct people** who recommended, saved, shared or tried it. This is what the user sees.
- **Internal score:** a weighted, time-decayed number used only for ordering.

### 6.3 Internal score (version 1) **[Proposal]**

Per person and dish, the contribution is the sum of their actions' weights, capped:

| Action | Weight | Rationale |
|---|---|---|
| Recommend | 1.0 | Passive approval |
| Save | 1.5 | Intent to go |
| Tried it | 2.0 | Real experience (S1) |
| Share | 2.5 | Active advocacy |

**[Assumption]** The weights follow your idea that all three signals count and that sharing is the strongest. Nothing in my research validates these numbers, so treat them as the starting values to tune with pilot data.

```
contribution(person, dish) = min(3.0, sum of weights of that person's actions on the dish,
                                  each multiplied by decay(age of that action))

decay(age) = 0.5 ^ (age_in_days / 45)        (half-life 45 days)  [Assumption]

score(dish) = sum over people of contribution(person, dish)
```

- **Cap of 3.0 per person** stops one account from adding 4 to 7 points by pressing every button.
- **Half-life 45 days** means an action from 45 days ago counts half. In a one-city launch with little data, a long half-life avoids an empty-looking feed. Tune later.

### 6.4 Which dishes appear and in what order

The feed mixes two lanes:

- **Top lane (80% of cards):** dishes with at least **3 distinct endorsers** (**[Assumption]**), ordered by `score` descending, scoped to the selected city and the optional category filter. Ties break by most recent endorsement.
- **Fresh lane (20% of cards, every fifth card):** dishes added in the last 14 days or with fewer than 3 endorsers, ordered randomly with a per-user seed so the user does not see the same fresh dish twice. This gives new posts a chance. (A common idea from recommender systems called exploration.)

**Distance** is a *filter and display*, not a ranking input in v1: show the city, display distance on each card, offer a "Nearby" tab that sorts by distance among dishes with at least one endorser.

**Followed people (S9):** if the user follows someone who recommended a dish, add +0.5 to that dish's score for that user. Done in the query, not stored.

### 6.5 Hidden gems **[Proposal]**

A dish gets a "Hidden gem" badge when:

1. It has at least **5 distinct endorsers** and is in the **top 30%** of scores in the city; **and**
2. Its restaurant is **quiet**, meaning either:
   - the restaurant has fewer than 15 posts in total and fewer than 20 distinct endorsers across all its dishes, **or**
   - the restaurant's user-submitted rating is **3.5 or lower** from at least 5 ratings (S5).

The second condition is the direct expression of "regardless of the restaurant's rating". Until S5 has enough ratings, the badge relies on the first condition. All thresholds are **[Assumption]**.

### 6.6 Why not rate-based ranking (Wilson lower bound) in version 1?

My research report suggested a Wilson lower bound as a small-sample guard. For the MVP I recommend a **simpler count-based score with a minimum of 3 endorsers**, and I am changing that earlier suggestion for these reasons:

1. In a one-city launch, almost everyone sees the same top dishes, so exposure is nearly equal. A rate (endorsements per viewer) mainly measures *rank position*, not quality, and creates a feedback loop.
2. Your stated principle is *how many people recommend*. A count matches that. A rate can rank a dish seen by 100 people with 12 recommendations above one seen by 400 people with 30, which would confuse a user who sees "Recommended by 30" below "Recommended by 12".
3. Wilson still ranks a 3-viewer dish with 2 positives above a 100-viewer dish with 12 positives unless a minimum is also set. A minimum endorser count is needed either way.

**What we do now:** record **impressions** from day one (a card shown on screen for at least 1 second), so a rate-based or Wilson-based score can be introduced later when exposure becomes uneven (more users, more cities). The formula for that version:

```
wilson_lb(pos, n, z) = ( p + z²/(2n) − z·sqrt( (p(1−p) + z²/(4n)) / n ) ) / ( 1 + z²/n )
where p = pos / n, n = distinct viewers, pos = distinct endorsers
```

### 6.7 Worked example

City: Nashik. Today is day 0. Weights as above. No decay shown, to keep the arithmetic readable.

| Dish | Recommends | Saves | Shares | Tried | Distinct people | Score |
|---|---|---|---|---|---|---|
| Misal at Cafe A | 20 | 10 | 3 | 5 | 30 | 20×1 + 10×1.5 + 3×2.5 + 5×2 = 20 + 15 + 7.5 + 10 = **52.5** (if no person exceeds the cap) |
| Cold coffee at Cafe B | 12 | 18 | 6 | 0 | 25 | 12 + 27 + 15 = **54.0** |
| Bhurji pav at Stall C (added 3 days ago) | 2 | 1 | 0 | 0 | 2 | Below the 3-endorser minimum, so it appears in the **fresh lane** |
| Dosa at Cafe D | 40 | 0 | 0 | 0 | 40 | **40.0** |

(The Misal and Cold coffee rows assume no single person hits the 3.0 cap. If someone saved and shared the same dish, their contribution would be capped at 3.0 instead of 4.0, and the totals would drop slightly.)

Order in the Top lane: Cold coffee (54.0), Misal (52.5), Dosa (40.0). Bhurji pav appears as every fifth card until it has three endorsers. If its restaurant has fewer than 15 posts and fewer than 20 endorsers in total, and it later reaches 5 endorsers and the top 30%, it earns a Hidden gem badge.

### 6.8 Abuse resistance (MVP level)

| Threat | Defence |
|---|---|
| One person pressing every button repeatedly | Unique key on (user, dish, action); cap of 3.0 per person |
| Many fake accounts | Google sign-in only in MVP; new accounts' actions count at 50% for their first 24 hours **[Assumption]**; rate limit actions per hour per account |
| Restaurant owner pumping their own dish | Show recommender handles; flag clusters of new accounts recommending the same place in a short window for manual review |
| Spam or off-topic posts | Reporting, auto-hide at 3 reports, admin queue |
| Paid promotion hidden as recommendation | Required sponsored label; no paid ranking |
| Review bombing a rival | No negative signal exists in the MVP (only positive actions); hiding is done through reports and moderation, not downvotes |

Having **no downvote** is deliberate. It matches Foodspotting's positive-only model and avoids review-bombing. The weakness is that bad dishes only fall by not being endorsed.

### 6.9 Making the ranking a function you can change

Implement the score as a database function and a scheduled refresh (section 8.4). Keep weights, half-life and thresholds in a `ranking_config` table so you can tune them without shipping a new app.

---

## 7. Content and community strategy

> Cold start is the failure that killed or crippled most predecessors **[Research]**. This section is as important as the code.

### 7.1 Seeding plan for one city

**Goal:** before any outsider sees the app, the live city has at least **150 dishes at 40 to 60 places**, with each major category (about 12) holding at least 8 dishes. **[Assumption]** Your earlier target of 500 posts is a good stretch goal, but the real threshold is *enough dishes per category and per neighbourhood that a user can find something within three swipes*.

**How to get there, by hand:**

1. **Pick the city** (section 16, decision D1). Choose a city where you can physically visit places. A small city is easier to cover by hand. Directory data show only one Nashik food creator at about 342k followers, so creator-led seeding there would be thin and personal visits matter more. **[Research]** (directory snapshots; indicative only).
2. **Build a place list:** walk or ride neighbourhood by neighbourhood, and also use Google Maps manually to list cafes, restaurants, stalls and sweet shops. Record name, locality, a map pin, and the 1 to 3 dishes the place is known for. Use the spreadsheet in Appendix E.
3. **Collect photos legitimately.** Options, in order of preference:
   - Photograph the dish yourself (best quality control and no rights issue).
   - Ask friends and classmates to contribute photos with written (message) consent that you can use them in the app.
   - Ask a cafe or stall owner for permission to use their dish photos, with a message you save.
   - Ask local creators to contribute with credit.
   Do **not** copy photos from Google Maps, Zomato or Instagram.
4. **Record consent and source** for every photo in the seed spreadsheet (who took it, how consent was given, date).
5. **Attribute posts** to a real handle (your own account or the contributor's) so every card has a face behind it, not "WorthAbite team".
6. **Add endorsements honestly.** Do not create fake accounts that recommend your own seeds. Recommendations must come from real people. If the feed looks too empty, show fewer, better dishes, not fake numbers. Early on, show "New" and "Be the first to recommend" instead of a "Recommended by 0" count.

### 7.2 Getting the first 100 real users (pilot)

**[Proposal]** (the numbers are assumptions)

- **Seed community of 15 to 20 people** (friends, classmates, flatmates) who each agree to recommend or post five dishes in the first week.
- **Expand to 50 to 100 users** through a "dish of the week" page on Instagram or a WhatsApp channel for the city (section 13.2). This both tests demand and drives installs.
- **Invite local food people** (5 to 10) to post. Offer visible credit: a "Local expert" label and a profile that shows their recommendations. Offer no cash. Foodspotting's cash-for-photos pilot ran at the company's own expense with no published results. **[Research]**
- **Talk to 5 to 10 small cafe or stall owners** and ask which dish they are proudest of. This both fills the app and begins relationships, but do not promise ranking.

### 7.3 Community rules and labels

1. **Positive-only actions** (no downvote).
2. **Honest labels:** a post must be marked sponsored if the poster was paid or received a free meal or other benefit. This follows the principle in ASCI's disclosure guidelines, under which free meals and barter count as a "material connection" (source: a law-firm summary; the primary page was not read). **[Research]**
3. **No restaurant can buy rank.** If restaurant promotion is added later, it must live in clearly marked slots outside the ranked lane.
4. **Moderation:** report button, auto-hide at three reports, a daily review of the queue by you. Content rules: only food, real photos, no personal faces without consent, no abuse.
5. **Takedown promptness:** respond to complaints fast. India's 2026 rules shorten takedown timelines to as little as 3 hours on a court or government order. **[Research]** Have a contact email ready from day one.

### 7.4 Instagram: what we do and do not do

**Do not scrape in the MVP.** Reasons, all **[Research]** except where noted:

1. The official API does not allow discovery of other people's posts by location. Hashtag search is limited to 30 unique hashtags per week, needs App Review and (per one secondary summary) returns only the last 24 hours without identifying the poster.
2. Unofficial scraping needs proxies and constant workarounds because Instagram actively blocks it. Vendors charge roughly $0.60 to $2.70 per 1,000 results (vendor-biased figures).
3. The Meta v. Bright Data ruling is a US trial-court decision about logged-out scraping and contract claims. It does not decide copyright or privacy, and does not bind India. Meta has won or settled other scraping cases where fake or logged-in accounts were used.
4. India's DPDP Act narrows the "publicly available" exemption, and the IT minister said in August 2024 that scraping public user data is covered. Main duties begin around mid-May 2027.
5. Copyright in photos stays with the creator. Re-displaying copied photos can lead to takedowns.
6. Dish.fm, which built on other platforms' data, closed.

**What we do instead:**

| Method | What it gives | Effort | When |
|---|---|---|---|
| Own seeding and user uploads | Original content you have the right to show | Medium | MVP |
| **"Link to Instagram" on a post** (C3) | A button that opens the creator's post in the Instagram app; we store only the URL | Low | MVP if time |
| **oEmbed display** of a pasted public post | The post appears via Instagram's own embed | Medium | After MVP. Meta restricts oEmbed to a front-end view, so we cannot mine it for data. A June 2026 change reportedly made oEmbed tokenless (trade blog; Meta docs still describe tokens). **[Verify]** |
| **Creator opt-in (OAuth)** | A food creator or restaurant connects their Professional account and licenses chosen posts to the app | High (needs Meta App Review and business verification) | After MVP |
| Licensed place data (Google Places IDs, Foursquare) | Place identity, not dish content | Low to medium | Place IDs only; Google restricts caching of reviews and photos. **[Research]** |

This still gives you the "hybrid content" you wanted, in a form that is safe to keep: your own seeds, user posts, and consented creator content, with Instagram as a link-out and later an opt-in source.

### 7.5 Dish vocabulary

- Start with **12 categories** (examples: Misal, Vada Pav and snacks, Dosa and South Indian, Biryani, Thali, Chai and coffee, Desserts and sweets, Street food, Burgers and sandwiches, Pizza and pasta, Chinese, Breakfast). Adjust to your city's food.
- Maintain a **dish alias table** mapping spellings and local names to a category and a canonical name. Seed it with 100 to 200 entries from your own knowledge and grow it from user input.
- When a user types a dish name, show suggestions from aliases first; allow free text if nothing matches. A moderator can later merge duplicates.
- Normalise names for matching: lower case, remove punctuation and extra spaces. Use trigram similarity in the database for fuzzy suggestions.

### 7.6 Incentives that cost nothing

- A visible **"Recommended by"** strip with avatars and handles.
- **Contributor badges** (for example "First to post this dish", "Local expert at 10 dishes").
- A **weekly "Top 10 dishes in [city]"** shareable image generated from the data. Good for growth and the Instagram demand test.
- **Streaks are deliberately skipped**, since Beli drew criticism that streaks can matter more than real dining. **[Research]** Reward posting real dishes, not daily check-ins.

---

## 8. Technical architecture and data model

### 8.1 Choices at a glance **[Proposal]**

| Layer | Choice | Why | Alternative |
|---|---|---|---|
| Mobile app | **React Native + Expo (development build, not Expo Go) + TypeScript** | One codebase now (Android) and iOS later; strong ecosystem for lists and images (FlashList, expo-image) | Kotlin with Jetpack Compose (best native Android performance, Android-only), or Flutter (benchmarks favour it on iOS, but one vendor blog) |
| Navigation | expo-router | File-based routing, deep links | React Navigation |
| Backend | **Supabase** (managed Postgres, Auth, Storage, Edge Functions) | SQL and geo queries fit ranking and "near me"; row-level security; fast to start | Firebase (Firestore is awkward for ranking and geo queries and per-interaction writes can cost more), or self-host Postgres on your own infrastructure (you can, but it costs setup time) |
| Geo | **PostGIS** extension | Distance and radius queries | Plain latitude/longitude with Haversine |
| Fuzzy search | **pg_trgm** extension | Typo tolerant dish and place search | An external search service later |
| Images | Client-side resize to two sizes, stored in Supabase Storage (or Cloudflare R2) behind a CDN | No paid image transform needed | Server-side transform if on a plan that includes it. **[Verify]** |
| Auth | Supabase Auth with Google Sign-In | No SMS cost | Phone OTP later |
| Analytics | **PostHog** (events, funnels, retention) | Free tier likely sufficient for a pilot. **[Verify]** | Firebase Analytics |
| Crash reports | **Sentry** (React Native) | Stack traces with source maps | Firebase Crashlytics |
| CI/CD | GitHub Actions + EAS Build (or local Gradle build) | Repeatable builds | Fastlane |
| Admin | Supabase dashboard + saved SQL + a small admin screen in the app for approving places and reviewing reports | No separate web admin to build | Retool or a Next.js admin |
| Domain/web | Tiny static site on Cloudflare Pages or Vercel for share links, privacy policy, terms and delete-account page | Required for Play Store and sharing | |

Because you work in DevOps, you could self-host Postgres and storage later. For an MVP, managed services save weeks. The schema is plain Postgres, so migration is straightforward.

### 8.2 System diagram

```
 ┌──────────────────────┐        HTTPS         ┌─────────────────────────────┐
 │  Android app (RN)    │ ───────────────────► │ Supabase                    │
 │  - Feed, detail, add │   REST / RPC / auth  │  - Auth (Google)            │
 │  - Image resize      │ ◄─────────────────── │  - Postgres + PostGIS       │
 │  - Local cache       │                      │     tables, RLS, RPC funcs  │
 └─────────┬────────────┘                      │  - pg_cron (rank refresh)   │
           │ images (CDN)                      │  - Storage (images)         │
           ▼                                   │  - Edge Functions (admin,   │
 ┌──────────────────────┐                      │     moderation hooks)       │
 │ Storage + CDN        │ ◄──────────────────  └──────────────┬──────────────┘
 └──────────────────────┘                                     │
                                                              ▼
 ┌──────────────────────┐   events         ┌───────────────────────────────┐
 │ PostHog / Sentry     │ ◄─────────────── │ Nightly pg_dump to a bucket   │
 └──────────────────────┘                  │ (your DevOps backup script)   │
                                           └───────────────────────────────┘
```

### 8.3 Data model (summary; full SQL in Appendix A)

```
cities ──< restaurants ──< dishes ──< posts
   │            │              │
   │            │              └──< engagements >── profiles
   │            └──< restaurant_ratings >── profiles
   └──< (home city of) profiles

dish_categories ──< dishes
dish_categories ──< dish_aliases
dishes ──< dish_views (impressions)
dishes ──1 dish_stats (derived)
reports (post/dish/user)    follows (profile→profile)    ranking_config
```

Key design choices:

1. **Dish listing is its own table** (`dishes`), one row per dish per restaurant. Engagements attach to the dish, not the photo. This is what lets you say "38 people recommend *this* dish" even when ten photos exist.
2. **Posts are photos of a dish.** The cover photo can be chosen automatically (most recent approved, or the creator's).
3. **Engagements use a composite primary key** `(user_id, dish_id, kind)`, which makes duplicates impossible.
4. **Impressions are recorded** in `dish_views`, with a device ID for guests, so a rate-based score can be introduced later.
5. **Derived tables** (`dish_stats`) are refreshed by a scheduled job, so the feed query is a cheap read.
6. **Row-level security (RLS)** is on for every table. The app only uses the public (anon) key plus the user's session, never a service key.

### 8.4 Feed query and refresh job

- A scheduled job (`pg_cron`) recomputes `dish_stats` every **5 minutes** (**[Assumption]**). At MVP scale (hundreds of dishes, thousands of engagements) a full recompute is fast enough. If it ever becomes slow, limit it to dishes with recent activity and run a full pass nightly.
- The feed is served by a database function `get_feed(city_id, category_id, tab, lat, lng, cursor, limit)` that:
  1. filters to the city and active dishes,
  2. computes the Top and Fresh lanes,
  3. interleaves them 4:1,
  4. returns dish rows with cover image paths, restaurant fields, endorser counts, whether the current user has recommended/saved, and distance.
- **Pagination:** because scores change, the cursor is a *rank offset into a per-request snapshot*, and the client **de-duplicates by dish ID**. Occasional repeats are acceptable in the MVP.
- **Caching:** the app caches the last feed response so reopening the app shows content immediately while a refresh runs.

### 8.5 Image pipeline

1. User picks a photo (camera or gallery).
2. App compresses and generates **two versions**: a main image (long edge about 1080 px, WebP or JPEG quality about 80) and a thumbnail (long edge about 360 px). **[Assumption]** sizes.
3. App uploads both to Storage under `posts/{user_id}/{post_id}/main.webp` and `thumb.webp`.
4. The database stores only the paths. Image URLs are built from a public bucket base URL.
5. The feed shows a **blurhash** placeholder (a few bytes stored in the post row) until the main image loads.
6. Remove EXIF location data from uploaded photos before upload. (Privacy: a photo can contain the exact location of a person's home.)
7. Typical size: a 1080 px WebP of a food photo is commonly in the 100 to 200 KB range **[Assumption]**, so 500 dishes with 2 photos each is roughly 100 to 200 MB of storage and low CDN traffic. Check the actual storage and bandwidth limits of the plan you choose. **[Verify]**

### 8.6 Security and privacy design

- RLS policies: anyone can read live dishes, posts and restaurants; only the owner can insert/update/delete their own engagements and posts; only admins can change `status` fields or `ranking_config`.
- Rate limits: insert functions check counts per user per hour (for example posts and engagements). **[Assumption]**
- Never put service-role keys in the app. Admin work uses the dashboard or Edge Functions that check an admin role.
- Store only what is needed: Google ID, email (for account recovery), handle, avatar. Do not store precise location history; the app sends coarse location only to pick a city (section 9.4).
- Encrypt in transit (HTTPS by default). Secrets live in environment variables / EAS secrets, not in the repository.
- Backups: a nightly `pg_dump` to a bucket you control (your DevOps strength), tested by a restore drill once before launch.

### 8.7 Observability

- Sentry for crashes and slow-render breadcrumbs.
- PostHog events listed in Appendix C.
- A simple dashboard of: daily active users, feed sessions, recommends per session, saves per session, posts per day, D1/D7 return, and moderation queue length.

---

## 9. Android app implementation details

### 9.1 Libraries **[Proposal]**

| Concern | Library | Notes |
|---|---|---|
| Framework | Expo (latest stable SDK at build time) with a development build | Expo Go cannot load every native module; plan a dev build from day one |
| Language | TypeScript (strict mode) | Fewer runtime bugs |
| Routing | `expo-router` | |
| Lists | `@shopify/flash-list` for lists; evaluate `react-native-pager-view` (vertical orientation) for the full-screen feed | Prototype both in week 1 and pick the smoother one |
| Images | `expo-image` | Disk and memory caching, `prefetch()`, blurhash placeholders, `recyclingKey` inside recycled lists |
| Gestures/animation | `react-native-gesture-handler`, `react-native-reanimated` | Button feedback, heart animation |
| Server state | `@tanstack/react-query` | Caching, retries, optimistic updates |
| Local state | `zustand` | City, session flags |
| Storage | `react-native-mmkv` for fast key-value cache | Persist query cache |
| Supabase | `@supabase/supabase-js` | |
| Auth | `@react-native-google-signin/google-signin` | |
| Location | `expo-location` | Coarse accuracy only |
| Camera/gallery | `expo-image-picker` | |
| Image resize | `expo-image-manipulator` | Two sizes before upload |
| Forms | `react-hook-form` + `zod` | Add-dish form validation |
| Analytics | `posthog-react-native` | |
| Crashes | `@sentry/react-native` | |
| Testing | Jest + React Native Testing Library; Maestro for end-to-end flows | |
| Lint/format | ESLint, Prettier | |

Versions change quickly. Pin exact versions when you scaffold the project and re-check that each package supports the current Expo SDK. **[Verify]**

### 9.2 Project structure

```
worthabite/
├─ app/                      # expo-router screens
│  ├─ (tabs)/
│  │  ├─ index.tsx           # Feed
│  │  ├─ search.tsx
│  │  ├─ saved.tsx
│  │  └─ profile.tsx
│  ├─ dish/[id].tsx
│  ├─ restaurant/[id].tsx
│  ├─ add.tsx
│  ├─ city.tsx
│  └─ settings.tsx
├─ src/
│  ├─ api/                   # supabase client, rpc wrappers, types
│  ├─ components/            # DishCard, ActionBar, CategoryChips, Avatar...
│  ├─ features/
│  │  ├─ feed/
│  │  ├─ engage/
│  │  ├─ post/
│  │  └─ auth/
│  ├─ hooks/
│  ├─ lib/                   # image resize, formatters, geo, analytics
│  ├─ store/                 # zustand
│  ├─ theme/                 # colors, spacing, typography
│  └─ i18n/                  # strings (en first)
├─ supabase/
│  ├─ migrations/            # SQL files from Appendix A
│  └─ seed/                  # seed CSVs and import script
├─ e2e/                      # Maestro flows
├─ app.config.ts
└─ eas.json
```

### 9.3 The feed screen in detail

**Layout.** Each item is exactly the height of the visible list area (screen minus the top bar and tab bar), so one card equals one page. Use a fixed aspect ratio for the photo area to keep layout stable and avoid re-measuring.

**Paging.** Prototype in week 1:
- *Option A:* FlashList with `snapToInterval` equal to the card height and `decelerationRate="fast"`.
- *Option B:* `react-native-pager-view` with `orientation="vertical"`.
Choose the one that holds 60 frames per second on a low-cost Android test phone with 30 cards loaded. Whichever you pick, keep the card component light: no nested scroll views, no heavy shadows, no per-card animations running when off-screen.

**Preloading.** When card *i* is visible, call `Image.prefetch()` for cards *i+1* to *i+3* (the main image) and *i+1* to *i+5* thumbnails. Meta's Reels uses adjacent-item preloading and pauses it under CPU or IO stress, which is the same idea at larger scale. **[Research]**

**Impressions.** Fire an impression event when a card has been at least 60% visible for at least 1 second. Batch events and send every 10 seconds or when the app backgrounds.

**Optimistic actions.** On Recommend or Save, update the count and button state instantly in the cache, send the request in the background, and roll back with a short message on failure. Queue actions made offline and retry (React Query mutation persistence).

**Empty and error states.** No network: show the cached feed with a banner. No dishes for a filter: show "Be the first to post a [category] in [city]" with a post button.

### 9.4 Location handling

1. On first launch, show a one-screen explanation: "We use your approximate location to show dishes near you."
2. Request **coarse (approximate)** location permission only. City-level matching does not need precise GPS.
3. Compute the nearest active city client-side from a small bundled list (city centres and radii), or by an RPC. Do not call a paid reverse-geocoding API.
4. If the user is more than the city radius away from every active city, show "WorthAbite is live in [city] only for now" with the option to browse that city.
5. Never store raw coordinates on the server, other than the coordinates of *restaurants* and optionally the user's chosen city. Distances are computed on the device from restaurant coordinates.
6. A vendor whitepaper reports higher opt-in when the app explains the benefit before the system prompt. **[Research]** (undated, not food-specific)

### 9.5 Add-dish flow in detail

1. Open picker (camera or gallery). Take image.
2. Crop/resize to two files (section 8.5). Strip EXIF.
3. Ask for dish name with live suggestions from the alias table (RPC with trigram search).
4. Ask for the place: query restaurants within 2 km of the device, nearest first; type-ahead filter; "Add a new place" asks for name, locality and drops a pin on a map (default to the current position). The new place has `status='pending'`.
5. Submit: upload files to Storage first, then call an RPC `create_post` that creates the dish if it does not exist (matching `restaurant_id` + normalised name), inserts the post and returns it.
6. Show the post in the user's profile at once. If the place is pending, show "Waiting for review".
7. Use idempotency keys so a retry does not duplicate the post.

### 9.6 Authentication flow

- Anonymous by default: the app generates a random `device_id` stored in MMKV, used for impression counting only.
- On a protected action (recommend, save, post, report), open the sign-in sheet. After sign-in, complete the pending action automatically.
- First sign-in creates a `profiles` row with a generated handle (editable once).
- Sign-out clears the session but keeps the device ID.

### 9.7 Performance targets **[Assumption]**

| Metric | Target | How to measure |
|---|---|---|
| Cold start to first card visible | under 2.5 s on a mid-range phone, 4G | Manual stopwatch plus Sentry/PostHog timing |
| Scroll smoothness in feed | 60 fps, no visible jank across 30 cards | Android "Profile GPU rendering" and Flipper/React DevTools |
| Next card ready when scrolled to | image visible in under 300 ms for prefetched cards | Debug logs |
| App size (AAB download) | under 40 MB | Play Console |
| Memory | no growth over 100 cards scrolled | Android Studio profiler |
| Crash-free sessions | 99% or better during the pilot | Sentry |

Test on a **low-cost Android phone** (2 to 3 GB RAM). Vendor benchmarks are not a substitute: Shopify's own FlashList claims and one vendor benchmark favouring Flutter do not tell you how your build performs. **[Research]**

### 9.8 Accessibility and localisation

- Content descriptions on all icon buttons; minimum touch target 48 dp; support system font scaling; sufficient colour contrast.
- Put all user-facing text in a strings file from the start (English first). Hindi and Marathi can be added later (C4).
- Support dark mode in the design tokens even if you ship light only.

### 9.9 Build, signing and CI

- Create the Android package name once (for example `app.worthabite`). It cannot be changed after publishing.
- Use **Play App Signing**. Keep the upload key backed up securely.
- Build an **Android App Bundle (.aab)** for release. Use EAS Build or a local Gradle build.
- GitHub Actions on every push: install, typecheck, lint, unit tests. A manual workflow builds a preview APK for testers.
- Environment variables (Supabase URL and anon key, PostHog key, Sentry DSN) live in EAS secrets or a local `.env` that is git-ignored.

---

## 10. Legal, privacy and Play Store compliance

> **I am not a lawyer and this is not legal advice.** Several points below rest on secondary sources (law-firm summaries and trade blogs), not on statute or platform text. Budget for one consultation with an Indian technology lawyer before public launch. **[Verify]**

### 10.1 Documents you need before the first outside tester

| Document | Purpose | Notes |
|---|---|---|
| **Privacy Policy** | Required by Play and by data-protection law | Say what you collect (Google account ID, email, handle, avatar, photos, approximate location, device ID for counts, usage events), why, who processes it (Supabase, PostHog, Sentry, Google), how long you keep it, how to delete it, and how to contact you. Host it on a public URL. |
| **Terms of Use** | Rules for using the app and posting | Include a content licence: users keep ownership but grant you a licence to host and show their posts in the app and in share previews. Ban copied photos, abusive content and fake endorsements. |
| **Community Guidelines** | What is allowed | Food-related, real photos, honest labels, no harassment. |
| **Sponsored content rule** | Disclosure | Required toggle and visible label. |
| **Grievance contact** | India's intermediary rules expect a grievance officer for platforms that host user content | Publish a name or role and an email, with a stated response time. **[Verify]** the exact current requirements. |
| **Delete-account web page** | Play requires a web URL for account deletion, as well as the in-app option | A page explaining how to delete and what is removed. **[Verify]** |

### 10.2 India's DPDP Act, in plain terms **[Research]** (secondary sources)

- The DPDP Rules were notified on 13 November 2025 and phase in over about 18 months. Main duties (notice and consent, safeguards, breach notification) begin around **mid-May 2027** (sources differ between 13 and 14 May; confirm in the official Gazette). A proposal to compress timelines was floated in January 2026; its final status was not found.
- You will be processing personal data (Google account details, photos, device identifiers, approximate location). Design now for: a clear notice, consent for location, a way to withdraw consent, data minimisation, deletion on request, reasonable security, and a plan if there is a breach.
- **Children:** the Act treats people under 18 as children and imposes extra conditions on processing their data. **[Verify]** The simplest safe design is an **18+ app** in the Terms. Check this with the lawyer.
- Penalty maxima in the Act are large (up to Rs 250 crore for security failures and Rs 50 crore for other breaches). They are caps, and no enforcement history exists yet.
- Avoid collecting what you do not need: no contact-list import, no precise location history, no phone numbers in the MVP.

### 10.3 Content and copyright

- Only publish photos you own or have written permission to use. Keep a consent record (seed spreadsheet, Appendix E).
- Do not republish Instagram, Zomato or Google photos.
- Faces: avoid photos with identifiable people unless they consented. Add a report reason for this.
- Respond promptly to takedown complaints. India's 2026 intermediary amendments (in force 20 February 2026) shortened some takedown timelines to as little as 3 hours on a court or government order. **[Research]**
- Positive-only actions reduce, but do not remove, defamation risk. Captions are free text, so moderate them.

### 10.4 Advertising and creator disclosure

- ASCI's influencer guidance (as summarised by a law firm) treats free meals and barter as a "material connection" that must be disclosed. The app's sponsored toggle and label follow this principle. **[Research]**
- Restaurant-supplied or creator-supplied posts must also be labelled.

### 10.5 Food information

- Veg/non-veg and allergen markers are user-provided and unverified. Show a short note: "Check with the restaurant for ingredients and allergens."
- Do not make health claims.

### 10.6 Google Play checklist **[Verify]** all items against the current Play Console

1. **Developer account:** a one-time registration fee applies (about US$25, from my recollection). Complete identity verification.
2. **New personal accounts** have been required to run a **closed test with at least 12 testers for 14 continuous days** before applying for production access. Start recruiting testers early. The rule has changed before, so check the current requirement.
3. **Target API level:** Play requires new apps to target a recent Android API level. The current Expo SDK handles this, but confirm before submission.
4. **App Bundle (.aab)** with Play App Signing.
5. **Data safety form:** declare what is collected and shared (location approximate, photos, personal info, app activity, device ID, crash logs).
6. **Content rating questionnaire:** user-generated content applies.
7. **User-generated content policy:** in-app report and block, moderation, and a way to contact you.
8. **Account deletion:** in-app option and web URL.
9. **Permissions:** declare only what you use. Approximate location; camera (only if you take photos inside the app); no background location; no contacts; no SMS.
10. **Store listing:** title, short and full description, icon (512 px), feature graphic, at least 2 phone screenshots, privacy policy URL, contact email.
11. **Ads:** declare "no ads" unless that changes.

---

## 11. Build plan: week by week

### 11.1 Honest estimate

Estimates are my own and assume one person who already knows TypeScript and basic mobile development. They include debugging time but not learning a new framework from scratch. **[Assumption]**

| Work package | Must list (hours) |
|---|---|
| Project setup, CI, design tokens, feed paging prototype | 14 |
| Backend: schema, RLS, RPC functions, ranking job | 22 |
| Auth (Google) and profile creation | 8 |
| Feed screen (cards, tabs, chips, prefetch, impressions) | 30 |
| Dish detail and restaurant pages | 14 |
| Recommend, save, share (optimistic, offline queue) | 12 |
| Browse by category | 8 |
| Saved list | 6 |
| Add-dish flow (picker, resize, upload, place picker) | 24 |
| Report, block, delete account, rate limits, admin screen | 14 |
| Seed tooling (CSV import, photo upload script) | 12 |
| Analytics and crash reporting | 6 |
| Performance tuning and polish | 14 |
| QA, bug fixing, device testing | 12 |
| Release preparation (store listing, policy pages, builds) | 10 |
| **Must list total** | **about 206** |

| Should list | Hours |
|---|---|
| S7 sponsored label | 3 |
| S1 "Tried it" | 4 |
| S2 Hidden gems tab | 8 |
| S3 Trending tab | 4 |
| S8 Profile page | 8 |
| S5 Restaurant rating (secondary) | 6 |
| S4 Search with fuzzy matching | 10 |
| S6 Share links with web preview | 10 |
| S9 Follow a person | 8 |
| **Should list total** | **about 61** |

**Total for everything: about 267 hours.** My earlier chat suggestion of "4 weeks" holds only if you work close to full time and cut scope.

### 11.2 Three scope options

| Option | Contents | Hours | Full-time (40 h/wk) | Part-time (13 h/wk) |
|---|---|---|---|---|
| **Lite** | Seed-only content, feed, detail, restaurant, categories, recommend/save/share, saved list, guest mode, Google sign-in, analytics, a simple "Suggest a dish" form instead of full posting | about 130 | about 3.5 weeks | about 10 weeks |
| **Standard** (Must list) | Lite plus user posting, moderation, deletion, admin tools | about 206 | about 5 weeks | about 16 weeks |
| **Full** (Must + Should) | Everything in this document | about 267 | about 6.5 weeks | about 20 weeks |

**Recommendation [Proposal]:** build in three releasable phases (below). Phase A is roughly the Lite scope. After Phase A, put the app in a few friends' hands. If they use it, continue. If not, you have lost about 130 hours, not 267.

### 11.3 Phases

#### Phase 0: Prepare (about 10 hours, in parallel with the demand test)

1. Make the decisions in section 16 (city, name, scope option, stack).
2. Create accounts: GitHub, Supabase, Google Cloud (for Google Sign-In), PostHog, Sentry, Expo, Google Play Console.
3. Start the demand test and interviews (section 13).
4. Start the seed spreadsheet: first 20 places and 40 dishes.
5. Finalise wireframes into clickable mock screens (Figma or even paper photos).
6. Write the one-page Terms and Privacy drafts.

**Exit criteria:** city chosen; at least 8 interviews done; demand test running; accounts ready.

#### Phase A: Seed and browse (about 130 hours)

Goal: a friend can install the app, see your city's dishes, and recommend or save them.

| Step | Tasks | Acceptance test |
|---|---|---|
| A1. Project and prototype | Create the Expo project, TypeScript, lint, CI; build a throwaway vertical pager with 30 fake cards; compare FlashList vs PagerView on a low-end phone | Chosen option holds smooth scrolling; decision recorded |
| A2. Backend | Apply schema (Appendix A); RLS policies; `refresh_dish_stats` and `get_feed` functions; ranking config; pg_cron job | SQL tests (section 12) pass; feed query returns in under 200 ms for 1,000 dishes **[Assumption]** |
| A3. Seeding | CSV import script; upload 60 dishes from the seed spreadsheet | 60 live dishes in the target city |
| A4. Auth | Google Sign-In, profile creation, session persistence | Sign in, kill the app, reopen: still signed in |
| A5. Feed | Feed screen, city sheet, category chips, tabs (Top only at first), prefetch, impressions, optimistic recommend and save | 20 cards load in under 3 s on 4G; actions work |
| A6. Pages | Dish detail, restaurant page, Open in Maps, saved list | Navigate every path without a crash |
| A7. Telemetry | PostHog events and Sentry | Events visible in the dashboard |
| A8. Internal alpha | Build an APK; give it to 3 to 5 friends | Collect crash reports and first impressions |

**Exit criteria:** alpha users can browse and engage; no crashes in a typical 10-minute session; you have seen real reactions.

#### Phase B: Post and moderate (about 40 hours)

Goal: users can add dishes safely.

| Step | Tasks | Acceptance test |
|---|---|---|
| B1. Add dish | Picker, resize, EXIF stripping, upload, dish suggestions, nearby place picker, add a new place (pending) | Post in under 30 s; image resized to two files |
| B2. Moderation | Report and block, auto-hide at three reports, admin approval screen for pending places and reported posts | Reported post disappears from the feed; admin can restore |
| B3. Limits | Rate limits and new-account weighting | Attempting 20 posts in a minute is rejected |
| B4. Account deletion | In-app deletion, web page | Test account is removed; posts follow the policy |
| B5. Policy pages | Host Privacy, Terms and Delete pages | URLs live |

**Exit criteria:** 15 to 20 pilot users can post and engage; moderation workflow works; policies are live.

#### Phase C: Should-haves and release (about 97 hours)

Order of priority **[Proposal]**: S7 sponsored label, S1 Tried it, S2 Hidden gems, S3 Trending, S8 Profile, S5 Rating, S4 Search, S6 Share links, S9 Follow. Skip from the bottom if time runs out.

Then: performance tuning, device-matrix testing, accessibility pass, store listing, Data safety form, closed testing track, fix bugs from testers, staged production rollout.

**Exit criteria:** closed test complete, no blocker bugs, store listing approved.

### 11.4 Week-by-week for a full-time schedule

If you work part-time, stretch each week below by about three (13 hours a week instead of 40).

| Week | Focus | Deliverable |
|---|---|---|
| **0** | Phase 0 | Decisions, accounts, demand test running, first seed rows |
| **1** | A1 to A3 | Pager decision, backend with ranking functions, 60 seeded dishes |
| **2** | A4 to A6 | Sign-in, working feed, detail and restaurant pages |
| **3** | A7, A8, then B1 | Telemetry, internal alpha APK to friends, begin posting flow |
| **4** | B1 to B5 | Posting, moderation, deletion, policy pages |
| **5** | C (S7, S1, S2, S3, S8) | Labels, Tried it, Hidden gems, Trending, Profile |
| **6** | C (S5, S4, polish) | Rating, search, performance, QA |
| **7** | Release | Closed test track opened; recruit 12 or more testers; fix issues |
| **8 to 9** | Closed test (14 days if required) | Collect feedback; staged rollout begins |

### 11.5 Day-level checklist for week 1 (example)

| Day | Tasks |
|---|---|
| Mon | Create repo, Expo app, TypeScript, lint, CI; theme tokens; write the throwaway pager with fake data |
| Tue | Test FlashList and PagerView on a low-end phone; record frame behaviour; pick one |
| Wed | Create Supabase project; enable extensions; apply the schema in Appendix A; write RLS |
| Thu | Write `refresh_dish_stats` and `get_feed`; insert a handful of fake rows; test queries and timings |
| Fri | Seed spreadsheet to CSV import script; load 20 real dishes; hook the pager to real data |
| Sat | Review with one friend; adjust card layout; write down what confused them |

### 11.6 Working method **[Proposal]**

- Work in short vertical slices: each slice goes UI to database and back, and is demoable.
- Commit daily. Tag each phase exit.
- Keep a `DECISIONS.md` with one line per decision and why.
- Keep a `TODO.md` of things you deliberately postponed. This prevents scope creep from silently eating weeks.
- Build with an AI assistant if you want to move faster, but read and test every generated change yourself, especially database policies and anything that handles user data.

---

## 12. Testing and release

### 12.1 What to test

| Area | How |
|---|---|
| **Ranking function** | SQL tests with fixed rows: caps per person, decay, min endorsers, tie-breaks, fresh lane interleaving, hidden-gem rules. Compare results to the worked example in section 6.7. |
| **Row-level security** | Try forbidden actions as a guest and as another user (read others' engagements, delete another's post, insert a dish directly, change `status`). All must fail. |
| **Feed performance** | Load 1,000 and 10,000 fake dishes; measure `get_feed` time with `EXPLAIN ANALYZE`; add indexes if needed. |
| **App flows** | Maestro scripts: first launch to feed; guest taps save, sign-in, action completes; add dish; report; delete account. |
| **Offline** | Airplane mode: cached feed shows; queued actions sync when back online. |
| **Permissions** | Deny and then grant location; deny camera; revoke mid-session. |
| **Devices** | At least 3 physical phones: low-end (2 to 3 GB RAM), mid-range, recent; at least two Android versions. |
| **Images** | Very large photo, wrong aspect ratio, photo with EXIF location, failed upload, slow network. |
| **Accessibility** | TalkBack walk-through of the feed and add flow; font scale 200%. |
| **Security** | Check that no secret keys are in the bundle; check that the anon key can do only what RLS allows. |
| **Abuse** | Try to inflate a dish with two accounts, rapid taps and repeated retries. |

### 12.2 Release tracks

1. **Internal testing** (immediate, up to your invited testers): daily builds for you and 3 to 5 friends.
2. **Closed testing**: 12 or more testers if Play still requires it for new personal accounts, 14 days. **[Verify]** Use this to run the pilot.
3. **Production**: staged rollout (10%, then 50%, then 100%), watching crash-free rate and complaints.

### 12.3 Definition of done for launch

- Crash-free sessions of 99% or better over the last week of the closed test.
- No open P1 bugs (data loss, wrong account actions, content from other users leaking).
- Policies and delete page live; Data safety form complete.
- Moderation drill done: you reported your own test post and restored it.
- Backup restore drill done once.
- At least 150 dishes live in the city across at least 8 categories.

---

## 13. Metrics, validation and kill criteria

### 13.1 What we need to learn

1. Do people want this? (demand)
2. Do they come back? (retention)
3. Do they recommend and save? (core action)
4. Do they post? (supply)
5. Is trust earned? (quality and moderation)

### 13.2 Before writing code: the demand test (1 to 2 weeks, almost free)

**[Proposal]** Create a simple "Dish of the week in [city]" page on Instagram or a WhatsApp channel.

1. Post 3 to 5 real dishes a week with a photo, the dish name, the place and one line on why. Use only photos you took or have permission to use.
2. Add a question at the end: "What dish should we feature next? Reply with yours."
3. Track followers or members, saves, shares and replies.
4. **Signals to watch** (thresholds are my judgement, not benchmarks): 50 or more followers/members from your own network in two weeks; 10 or more unprompted dish suggestions from people; at least 3 shares.
5. Why it is useful: it tests demand, builds the list of first users, and seeds the first dishes.

### 13.3 Interviews (15 to 20, 15 minutes each)

Use the script in Appendix D. The goal is to understand current behaviour, not to pitch. Ask the person to show you the last time they decided where or what to eat.

**Good signs:** they already follow food pages; they have been disappointed by a highly rated place; they spontaneously mention specific dishes; they share food recommendations to friends.
**Bad signs:** they say "I just use Zomato" without frustration; they never post or share food; they do not recall a recent recommendation.

### 13.4 Pilot metrics (with 50 to 100 users over 4 weeks)

| Metric | Definition | Starting target **[Assumption]** |
|---|---|---|
| Activation | New user recommends or saves at least one dish in the first session | 40% or more |
| Core actions per active user | Recommends plus saves per weekly active user | 3 or more per week |
| Week-1 return | Share of new users who open the app on any day in days 2 to 7 | 25% or more |
| Week-4 return | Share of week-1 users who open the app in week 4 | 10% or more |
| Posting rate | Share of weekly active users who add a dish in the week | 5% or more |
| Time to first card | Seconds from open to first card visible | under 2.5 s median |
| Crash-free sessions | Sessions without a crash | 99% or more |
| Reports per 100 posts | Moderation load | under 5 |

These are starting points chosen to be demanding but achievable for a tiny, friendly pilot. They are not industry benchmarks, and a pilot of 50 to 100 friendly users will flatter the numbers. The "200 daily users from 1,000 installs in week 4" target from my first brief would require a 20% daily-active rate, which I consider a stretch. **[Assumption]**

### 13.5 Kill, pivot and continue criteria **[Proposal]**

Set these now, before emotion arrives.

| After... | If... | Then |
|---|---|---|
| Demand test and interviews | Fewer than 5 of 15 interviewees see a real gap, and the page gets almost no engagement | Rethink the angle (for example a creator tool or a restaurant-side product) before building |
| Phase A alpha | Friends open it once and do not return | Fix feed quality and content depth; do not build posting yet |
| 4-week pilot | Week-4 return is under 5% and fewer than 3% of users ever post | Pause. Interview dropouts. Decide whether to change the content approach, the city or the idea |
| 4-week pilot | Returns are healthy but posting is near zero | Make posting easier or add creator onboarding; seeding must continue |
| 4-week pilot | Strong return and steady posting | Plan the second city and consider creator tools, with revenue questions in section 17 |

---

## 14. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | **Cold start**: too few dishes, feed feels empty | High | High | Hand-seed 150+ dishes; fresh lane; honest empty states; recruit seed community |
| R2 | **No revenue plan** (killed Foodspotting and Forkly) | High | High over time | Keep costs near zero; decide revenue only after retention is known; do not charge restaurants for listing (Burrp lesson) |
| R3 | **Existing competitor** (FavHiker, Swiggy, Google) closes the gap | Medium | Medium | Stay local and trusted; check competitors monthly; move fast on the hidden-gem lane |
| R4 | **Demand is weaker than hoped** (no India-wide data found) | Medium | High | Demand test, interviews, kill criteria |
| R5 | **Instagram policy and legal risk** | Medium | High | No scraping; link-out only; opt-in creators later |
| R6 | **Spam and fake endorsements** | Medium | Medium | Google-only sign-in, caps, new-account weighting, report flow, review of clusters |
| R7 | **Content rights complaints** | Medium | Medium | Own or consented photos; consent log; fast takedown |
| R8 | **Sponsored posts erode trust** | Medium | High | Mandatory label; no paid ranking |
| R9 | **DPDP and platform compliance** | Medium | Medium | Minimise data; consent for location; deletion; lawyer check |
| R10 | **Solo-builder bandwidth** (job plus MCA plus app) | High | High | Phased releasable milestones; Lite fallback; weekly time budget; strict scope list |
| R11 | **Performance on low-end phones** | Medium | Medium | Test early on a cheap phone; prefetch; light cards |
| R12 | **Play Store delays** (closed-test requirement, review) | Medium | Medium | Start recruiting testers in Phase B; keep documents ready |
| R13 | **Data loss** | Low | High | Nightly dumps; restore drill |
| R14 | **Ranking feels wrong** | Medium | Medium | Config table; weekly review of top 20 list against local knowledge; adjust weights |
| R15 | **Moderation workload** | Low initially | Medium | Positive-only actions, auto-hide threshold, daily 10-minute review |
| R16 | **Name or trademark clash** | Unknown | Medium | Check name, domain and Play Store before branding |
| R17 | **Feed rewards photogenic over tasty** (VICE critique) | Medium | Medium | Show recommender counts and "tried by" signals; do not use skips as negative votes |

---

## 15. Costs

All amounts must be confirmed against current price pages. **[Verify]**

| Item | Type | Expected cost |
|---|---|---|
| Google Play developer registration | One-time | Small one-time fee (about US$25 from my recollection) |
| Domain name | Yearly | Low annual cost |
| Supabase | Monthly | Free tier for development and the earliest pilot, then a paid plan when you need daily backups, more storage or no auto-pause (the Pro plan was around US$25 a month when I last knew it) |
| Image storage and CDN | Monthly | Small at pilot scale (see 8.5); check the plan's storage and bandwidth limits |
| PostHog and Sentry | Monthly | Free tiers are likely enough for a pilot |
| EAS Build | Monthly | Free tier with queue limits; local Gradle builds are free |
| Test phone(s) | One-time | A low-cost Android phone for performance testing, if you do not already have one |
| Legal consultation | One-time | A single review of Terms, Privacy and DPDP posture |
| Seeding costs | One-time | Your time, travel, and perhaps small thank-you treats for contributors |
| Design | Optional | A designer for the logo and icon, or use a simple wordmark |

**Largest hidden cost:** your time. At about 267 hours, this is the investment to protect with the kill criteria in 13.5.

---

## 16. Decisions you need to make

| # | Decision | Options | My default **[Proposal]** |
|---|---|---|---|
| D1 | **Launch city** | Your own city, or a larger one | The city where you can personally visit 40 to 60 places. If that is Nashik, plan for hand-seeding because creator density looked low in directory data. |
| D2 | **App name** | WorthAbite (placeholder) or another | Pick a name, then check Play Store, domain and trademark search |
| D3 | **Scope option** | Lite, Standard or Full | Standard in three phases; stop after Phase A if the alpha shows no pull |
| D4 | **Feed style** | Vertical paging (recommended) or right/left swipe cards (your original) | Vertical as default. If you love swipe cards, build them as an optional mode (C1) after launch so you can compare |
| D5 | **Instagram** | No scraping; link-out only (recommended) or scrape | No scraping in the MVP |
| D6 | **Ranking** | Count-based with caps (recommended) or rate-based | Count-based; log impressions for later |
| D7 | **Ranking weights** | 1 / 1.5 / 2 / 2.5 or your own | Use these as starting values, tune with data |
| D8 | **Stack** | React Native + Expo + Supabase, or Kotlin + Firebase/Supabase, or Flutter | React Native + Expo + Supabase. Choose Kotlin if you care only about Android and want maximum native feel. |
| D9 | **Sign-in** | Google only, or add phone OTP | Google only; guest browsing |
| D10 | **Age** | 18+ or lower | 18+ (check with the lawyer) |
| D11 | **Who can add restaurants** | Admin only, or any user with review | Any signed-in user, pending until reviewed |
| D12 | **Revenue** | Decide later (recommended) | Decide after the pilot |
| D13 | **Who else is on the team** | Solo, or with a friend for design/content | Even one content partner who photographs dishes helps seeding a lot |
| D14 | **Hours per week** | Set a number and protect it | State a number you can keep |

### 16.1 Immediate next steps (this week)

1. Answer D1, D2, D3, D4.
2. Install FavHiker, Swiggy (Eatlists) and Google Maps and spend an hour in each, searching for dishes in your city. Note what is good and what is missing. This closes the largest gaps in the research.
3. Start the "Dish of the week" page and book your first five interviews.
4. Start the seed spreadsheet (20 places).
5. Create the Supabase project and the GitHub repo.
6. Ask me to scaffold the project (Expo app, Supabase migrations, seed script) when you are ready.

---

## 17. After the MVP: roadmap

**Version 1.1 (weeks after launch)**
- Finish the Should list: search, share links, follow people.
- Horizontal swipe experiment (C1) behind a flag, measured against vertical.
- Weekly "Top 10 dishes in [city]" image generator.
- Hindi/Marathi strings.

**Version 1.2**
- Creator opt-in: connect an Instagram Professional account and license chosen posts (needs Meta App Review and business verification).
- Instagram oEmbed display for pasted links (check Meta's current terms).
- Notifications (new dish near you, a dish you saved is trending).
- Rate-based or Wilson-based ranking once exposure becomes uneven.

**Version 2**
- iOS app (same codebase).
- Second city using the same seed method.
- A friends layer (contacts or invites) and a taste-similarity score (Beli's idea). Check DPDP implications of contact access.
- Restaurant claim page (free), where owners can add dish photos and prices. No paid ranking.

**Possible revenue ideas, later, with precedents [Research]**

| Idea | Precedent | Caution |
|---|---|---|
| Consumer subscription | FavHiker Premium (Rs 499 a year in India); Beli's Supper Club ($74.99 a year) | Needs real extra value; guests must still browse freely |
| Restaurant offers sold to diners | Burpple Beyond (1-for-1 deals, $9.90 a month); EazyDiner Prime | Needs restaurant sales and payments |
| Sponsored dish slots outside the ranked lane | Foodspotting's untested plan; Dineout advertising | Must be labelled; never in the ranked lane |
| Creator tools | Not documented | Unknown demand |
| Charging restaurants for listing | Burrp charged restaurants for listings while Zomato listed them free (YourStory's analysis of its decline) | Avoid |

---

## 18. Appendices

### Appendix A: Database schema and functions (starting point)

> These scripts are a starting draft for Supabase (Postgres 15 or later with PostGIS and pg_trgm). Test them. Supabase enables extensions through its dashboard or SQL. Adjust names to your final conventions.

```sql
-- ============ Extensions ============
create extension if not exists postgis;
create extension if not exists pg_trgm;
create extension if not exists pgcrypto;

-- ============ Config ============
create table ranking_config (
  key   text primary key,
  value numeric not null,
  note  text
);
insert into ranking_config (key, value, note) values
  ('w_recommend',      1.0, 'weight of a recommend'),
  ('w_save',           1.5, 'weight of a save'),
  ('w_tried',          2.0, 'weight of a tried-it'),
  ('w_share',          2.5, 'weight of a share'),
  ('person_cap',       3.0, 'max contribution of one person to one dish'),
  ('half_life_days',  45.0, 'decay half-life in days'),
  ('min_endorsers_top',3.0, 'distinct endorsers needed to enter the Top lane'),
  ('fresh_days',      14.0, 'age window for the fresh lane');

-- ============ Geography ============
create table cities (
  id        serial primary key,
  slug      text unique not null,
  name      text not null,
  state     text,
  center    geography(point, 4326) not null,
  radius_km int not null default 25,
  is_active boolean not null default false
);

-- ============ People ============
create table profiles (
  id                 uuid primary key references auth.users(id) on delete cascade,
  handle             text unique not null check (handle ~ '^[a-z0-9_]{3,20}$'),
  display_name       text,
  avatar_url         text,
  home_city_id       int references cities(id),
  role               text not null default 'user'
                       check (role in ('user','creator','restaurant','admin')),
  is_verified_creator boolean not null default false,
  created_at         timestamptz not null default now(),
  deleted_at         timestamptz
);

-- ============ Places ============
create table restaurants (
  id            uuid primary key default gen_random_uuid(),
  city_id       int not null references cities(id),
  name          text not null,
  name_norm     text not null,
  locality      text,
  address       text,
  geo           geography(point, 4326) not null,
  maps_url      text,
  instagram_handle text,
  price_band    smallint check (price_band between 1 and 4),
  status        text not null default 'active'
                  check (status in ('active','pending','hidden')),
  created_by    uuid references profiles(id),
  created_at    timestamptz not null default now()
);
create index restaurants_geo_idx   on restaurants using gist (geo);
create index restaurants_name_trgm on restaurants using gin (name_norm gin_trgm_ops);
create index restaurants_city_idx  on restaurants (city_id, status);

-- ============ Dish vocabulary ============
create table dish_categories (
  id   serial primary key,
  slug text unique not null,
  name text not null,
  sort int not null default 0
);
create table dish_aliases (
  id          serial primary key,
  category_id int references dish_categories(id),
  canonical   text not null,
  alias       text not null unique,        -- stored normalised
  alias_norm  text not null
);
create index dish_aliases_trgm on dish_aliases using gin (alias_norm gin_trgm_ops);

-- ============ Dishes (one dish at one restaurant) ============
create table dishes (
  id            uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  city_id       int  not null references cities(id),
  category_id   int references dish_categories(id),
  name          text not null,
  name_norm     text not null,
  description   text,
  price_inr     int check (price_inr is null or price_inr between 1 and 20000),
  is_veg        boolean,
  cover_post_id uuid,
  status        text not null default 'live'
                  check (status in ('live','pending','hidden')),
  created_by    uuid references profiles(id),
  created_at    timestamptz not null default now(),
  unique (restaurant_id, name_norm)
);
create index dishes_city_idx     on dishes (city_id, status, category_id);
create index dishes_name_trgm    on dishes using gin (name_norm gin_trgm_ops);

-- ============ Posts (photos of a dish) ============
create table posts (
  id            uuid primary key default gen_random_uuid(),
  dish_id       uuid not null references dishes(id) on delete cascade,
  author_id     uuid references profiles(id) on delete set null,
  caption       text check (char_length(caption) <= 280),
  image_path    text not null,
  thumb_path    text not null,
  blurhash      text,
  width         int,
  height        int,
  source        text not null default 'user'
                  check (source in ('seed','user','creator')),
  photo_credit  text,                       -- handle for seeded photos
  is_sponsored  boolean not null default false,
  sponsor_note  text,
  instagram_url text,                       -- link-out only, never copied content
  status        text not null default 'live'
                  check (status in ('live','pending','hidden')),
  created_at    timestamptz not null default now()
);
create index posts_dish_idx on posts (dish_id, status, created_at desc);

alter table dishes add constraint dishes_cover_fk
  foreign key (cover_post_id) references posts(id) on delete set null;

-- ============ Engagement ============
create table engagements (
  user_id    uuid not null references profiles(id) on delete cascade,
  dish_id    uuid not null references dishes(id)   on delete cascade,
  kind       text not null check (kind in ('recommend','save','tried','share')),
  created_at timestamptz not null default now(),
  primary key (user_id, dish_id, kind)
);
create index engagements_dish_idx on engagements (dish_id, created_at desc);
create index engagements_user_idx on engagements (user_id, kind, created_at desc);

-- ============ Impressions (for a later rate-based score) ============
create table dish_views (
  id         bigint generated always as identity primary key,
  dish_id    uuid not null references dishes(id) on delete cascade,
  user_id    uuid references profiles(id) on delete set null,
  device_id  text,
  viewed_at  timestamptz not null default now()
);
create index dish_views_dish_idx on dish_views (dish_id, viewed_at desc);

-- ============ Secondary restaurant rating (S5) ============
create table restaurant_ratings (
  user_id       uuid not null references profiles(id) on delete cascade,
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  stars         smallint not null check (stars between 1 and 5),
  created_at    timestamptz not null default now(),
  primary key (user_id, restaurant_id)
);

-- ============ Follows (S9) ============
create table follows (
  follower_id uuid not null references profiles(id) on delete cascade,
  followee_id uuid not null references profiles(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);

-- ============ Reports and blocks ============
create table reports (
  id          bigint generated always as identity primary key,
  reporter_id uuid not null references profiles(id) on delete cascade,
  target_type text not null check (target_type in ('post','dish','user')),
  target_id   uuid not null,
  reason      text not null check (reason in
                ('wrong_place','not_food','inappropriate','spam','other')),
  note        text,
  created_at  timestamptz not null default now(),
  unique (reporter_id, target_type, target_id)
);
create table blocks (
  blocker_id uuid not null references profiles(id) on delete cascade,
  blocked_id uuid not null references profiles(id) on delete cascade,
  primary key (blocker_id, blocked_id)
);

-- ============ Derived stats ============
create table dish_stats (
  dish_id         uuid primary key references dishes(id) on delete cascade,
  endorsers       int  not null default 0,
  recommends      int  not null default 0,
  saves           int  not null default 0,
  shares          int  not null default 0,
  tried           int  not null default 0,
  score           numeric not null default 0,
  score_7d        numeric not null default 0,
  last_engaged_at timestamptz,
  updated_at      timestamptz not null default now()
);

-- ============ Helpers ============
create or replace function norm_name(t text) returns text
language sql immutable as $$
  select regexp_replace(
           trim(regexp_replace(lower(coalesce(t,'')), '[^[:alnum:] ]+', ' ', 'g')),
           '\s+', ' ', 'g')
$$;  -- test with Devanagari input; [:alnum:] depends on database locale

-- ============ Ranking refresh ============
create or replace function refresh_dish_stats() returns void
language plpgsql security definer set search_path = public as $$
declare
  w_rec numeric; w_sav numeric; w_tri numeric; w_sha numeric;
  cap numeric;   hl numeric;
begin
  select value into w_rec from ranking_config where key = 'w_recommend';
  select value into w_sav from ranking_config where key = 'w_save';
  select value into w_tri from ranking_config where key = 'w_tried';
  select value into w_sha from ranking_config where key = 'w_share';
  select value into cap   from ranking_config where key = 'person_cap';
  select value into hl    from ranking_config where key = 'half_life_days';

  with act as (
    select e.user_id, e.dish_id, e.kind, e.created_at,
           case e.kind when 'recommend' then w_rec
                       when 'save'      then w_sav
                       when 'tried'     then w_tri
                       when 'share'     then w_sha end as base
    from engagements e
    join dishes d on d.id = e.dish_id and d.status = 'live'
  ),
  per_user as (
    select user_id, dish_id,
      least(cap, sum(base * power(0.5,
          extract(epoch from (now() - created_at)) / 86400.0 / hl)))      as contrib,
      least(cap, coalesce(sum(base) filter
          (where created_at > now() - interval '7 days'), 0))             as contrib_7d
    from act
    group by user_id, dish_id
  ),
  agg as (
    select dish_id, count(*) as endorsers,
           sum(contrib) as score, sum(contrib_7d) as score_7d
    from per_user group by dish_id
  ),
  kinds as (
    select dish_id,
      count(*) filter (where kind = 'recommend') as recommends,
      count(*) filter (where kind = 'save')      as saves,
      count(*) filter (where kind = 'share')     as shares,
      count(*) filter (where kind = 'tried')     as tried,
      max(created_at)                            as last_engaged_at
    from act group by dish_id
  )
  insert into dish_stats
    (dish_id, endorsers, recommends, saves, shares, tried,
     score, score_7d, last_engaged_at, updated_at)
  select a.dish_id, a.endorsers, k.recommends, k.saves, k.shares, k.tried,
         a.score, a.score_7d, k.last_engaged_at, now()
  from agg a join kinds k using (dish_id)
  on conflict (dish_id) do update set
    endorsers = excluded.endorsers, recommends = excluded.recommends,
    saves = excluded.saves, shares = excluded.shares, tried = excluded.tried,
    score = excluded.score, score_7d = excluded.score_7d,
    last_engaged_at = excluded.last_engaged_at, updated_at = now();

  -- remove stats for dishes that no longer have engagements
  delete from dish_stats s
  where not exists (select 1 from engagements e where e.dish_id = s.dish_id);
end $$;

-- schedule every 5 minutes (requires pg_cron)
-- select cron.schedule('refresh-dish-stats', '*/5 * * * *', 'select refresh_dish_stats()');

-- ============ Feed (Top tab shown; other tabs are variations) ============
-- Slot rule: every 5th slot is a fresh-lane dish; the rest are top-lane dishes.
--   top rank k   -> slot k + floor((k-1)/4)
--   fresh rank m -> slot 5*m
create or replace function get_feed(
  p_city_id     int,
  p_category_id int  default null,
  p_limit       int  default 20,
  p_offset      int  default 0,
  p_seed        text default ''
) returns table (
  dish_id uuid, dish_name text, price_inr int, is_veg boolean,
  restaurant_id uuid, restaurant_name text, locality text,
  lat double precision, lng double precision,
  cover_image text, cover_thumb text, cover_blurhash text, is_sponsored boolean,
  endorsers int, recommends int, saves int,
  i_recommended boolean, i_saved boolean, lane text
) language sql stable as $$
  with cfg as (
    select (select value from ranking_config where key='min_endorsers_top')::int as min_end,
           (select value from ranking_config where key='fresh_days')::int        as fresh_days
  ),
  base as (
    select d.id, d.name, d.price_inr, d.is_veg, d.restaurant_id, d.cover_post_id,
           d.created_at as dish_created,
           coalesce(s.endorsers,0)  as endorsers,
           coalesce(s.recommends,0) as recommends,
           coalesce(s.saves,0)      as saves,
           coalesce(s.score,0)      as score,
           s.last_engaged_at
    from dishes d
    left join dish_stats s on s.dish_id = d.id
    join restaurants r on r.id = d.restaurant_id and r.status = 'active'
    where d.city_id = p_city_id and d.status = 'live'
      and (p_category_id is null or d.category_id = p_category_id)
  ),
  top as (
    select b.*, row_number() over
             (order by score desc, last_engaged_at desc nulls last, id) as k
    from base b, cfg
    where b.endorsers >= cfg.min_end
  ),
  fresh as (
    select b.*, row_number() over (order by md5(b.id::text || p_seed)) as m
    from base b, cfg
    where b.endorsers < cfg.min_end
       or b.dish_created > now() - make_interval(days => cfg.fresh_days)
  ),
  slotted as (
    select id, 'top'::text as lane, (k + floor((k-1)/4.0))::int as slot from top
    union all
    select id, 'fresh', (5*m)::int from fresh
  ),
  deduped as (
    select distinct on (id) id, lane, slot from slotted order by id, slot
  ),
  page as (
    select * from deduped order by slot offset p_offset limit p_limit
  )
  select b.id, b.name, b.price_inr, b.is_veg,
         r.id, r.name, r.locality,
         st_y(r.geo::geometry), st_x(r.geo::geometry),
         p.image_path, p.thumb_path, p.blurhash, coalesce(p.is_sponsored,false),
         b.endorsers, b.recommends, b.saves,
         exists (select 1 from engagements e where e.user_id = auth.uid()
                  and e.dish_id = b.id and e.kind = 'recommend'),
         exists (select 1 from engagements e where e.user_id = auth.uid()
                  and e.dish_id = b.id and e.kind = 'save'),
         pg.lane
  from page pg
  join base b        on b.id = pg.id
  join restaurants r on r.id = b.restaurant_id
  left join posts p  on p.id = b.cover_post_id
  order by pg.slot;
$$;
-- Variations:
--   trending : order the top lane by score_7d instead of score
--   gems     : top lane filtered by the hidden-gem rule in section 6.5
--   near     : order by st_distance(r.geo, st_makepoint(lng, lat)::geography)
-- Note: the Top and Fresh lanes are deduplicated so a dish never appears twice.

-- ============ Recommenders strip (public-safe fields only) ============
create or replace function get_recommenders(p_dish uuid, p_limit int default 8)
returns table (handle text, avatar_url text)
language sql stable security definer set search_path = public as $$
  select pr.handle, pr.avatar_url
  from engagements e
  join profiles pr on pr.id = e.user_id and pr.deleted_at is null
  where e.dish_id = p_dish and e.kind in ('recommend','tried')
  order by e.created_at desc
  limit p_limit;
$$;

-- ============ Row-level security (examples) ============
alter table profiles          enable row level security;
alter table restaurants       enable row level security;
alter table dishes            enable row level security;
alter table posts             enable row level security;
alter table engagements       enable row level security;
alter table dish_stats        enable row level security;
alter table dish_views        enable row level security;
alter table restaurant_ratings enable row level security;
alter table follows           enable row level security;
alter table reports           enable row level security;
alter table blocks            enable row level security;
alter table ranking_config    enable row level security;

-- public read of live content (ranking_config must be readable because get_feed reads it)
create policy read_config      on ranking_config for select using (true);
create policy read_restaurants on restaurants for select using (status = 'active');
create policy read_dishes      on dishes      for select using (status = 'live');
create policy read_posts       on posts       for select using (status = 'live');
create policy read_dish_stats  on dish_stats  for select using (true);
create policy read_profiles    on profiles    for select using (deleted_at is null);

-- own engagements only
create policy eng_select on engagements for select using (user_id = auth.uid());
create policy eng_insert on engagements for insert with check (user_id = auth.uid());
create policy eng_delete on engagements for delete using (user_id = auth.uid());

-- own ratings, follows, reports, blocks
create policy rate_all   on restaurant_ratings for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy follow_all on follows for all
  using (follower_id = auth.uid()) with check (follower_id = auth.uid());
create policy report_ins on reports for insert with check (reporter_id = auth.uid());
create policy block_all  on blocks  for all
  using (blocker_id = auth.uid()) with check (blocker_id = auth.uid());

-- impressions: insert only, no read from the app
create policy view_ins on dish_views for insert with check (true);

-- NO direct insert/update policies on dishes, posts or restaurants:
-- creation goes through security-definer RPC functions that check rate limits,
-- normalise names, and set status. Admin changes go through the dashboard.
-- Also revoke execute on refresh_dish_stats from anon/authenticated.
```

### Appendix B: App-to-backend contract (RPC list)

| Function | Inputs | Output | Notes |
|---|---|---|---|
| `get_feed` | city, category, limit, offset, seed | list of feed items | Section 8.4 and Appendix A |
| `get_dish` | dish id | dish with all live posts, restaurant, stats, my actions | One round trip for the detail page |
| `get_restaurant` | restaurant id | restaurant and ranked dishes | |
| `get_recommenders` | dish id, limit | handle and avatar list | Public-safe fields |
| `toggle_engagement` | dish id, kind | new state and counts | Idempotent; rate limited |
| `search` | text, city | dishes and restaurants | Trigram and alias matching (S4) |
| `suggest_dish_names` | text, city | list from aliases and existing dishes | For the add flow |
| `nearby_restaurants` | lat, lng, radius | restaurants | PostGIS distance |
| `create_post` | dish name, restaurant id (or new place payload), image paths, caption, price, sponsored flag, idempotency key | the post | Creates dish if needed; sets status |
| `report_content` | type, id, reason, note | ok | Auto-hide at three distinct reporters |
| `record_views` | list of dish ids, device id | ok | Batched impressions |
| `delete_my_account` | none | ok | Anonymises or removes per policy |

### Appendix C: Analytics events

| Event | Properties |
|---|---|
| `app_open` | cold_start (bool), signed_in |
| `location_permission_result` | granted / denied / coarse |
| `city_confirmed` | city, method (auto / picker) |
| `feed_viewed` | tab, category, city |
| `card_impression` | dish_id, position, lane |
| `recommend_toggled` / `save_toggled` / `tried_toggled` | dish_id, on/off, position |
| `share_tapped` | dish_id, channel |
| `dish_opened` / `restaurant_opened` | id, source |
| `open_maps_tapped` | dish_id |
| `category_selected` / `tab_selected` | value |
| `search_performed` | query_length, results |
| `signin_prompt_shown` / `signin_completed` | trigger_action |
| `add_dish_started` / `photo_picked` / `submitted` / `failed` | seconds_elapsed, reason |
| `report_submitted` | target_type, reason |
| `delete_account_started` / `completed` | |
| `time_to_first_card` | milliseconds |

Do not send names, emails or precise locations to analytics.

### Appendix D: Interview script and demand test

**Opening (1 min).** "I'm exploring a food app idea. I am not selling anything. I want to hear how you decide what to eat."

**Questions (12 min).**
1. Tell me about the last time you went out to eat. How did you pick the place?
2. What did you open or check? Zomato, Google Maps, Instagram, friends?
3. Did you decide on the place first, or on the dish first?
4. Has a highly rated place ever disappointed you? What happened?
5. Has anyone ever told you "you must order this one dish"? Who, and what did you do?
6. How do you save places or dishes you want to try? (Show me.)
7. Do you trust ratings? Reviews? Food pages on Instagram? Why or why not?
8. Do you ever post or message about a dish you loved? Where?
9. (Show the wireframes.) What would you do first? What is confusing?
10. What would make you open an app like this every week?
11. What would make you stop using it?
12. Who else should I speak to?

**Closing (2 min).** Ask permission to message them when there is something to test. Ask whether they would be one of the first five dishes they would post.

**What to record:** quotes, the tools they actually open, specific dishes they mention (these seed your list), and whether they would recommend a dish in an app.

### Appendix E: Seed spreadsheet columns

**Sheet 1: restaurants**

| Column | Example |
|---|---|
| restaurant_key | `cafe_ananda_college_rd` |
| name | Cafe Ananda |
| locality | College Road |
| address | (optional) |
| lat | 20.0059 |
| lng | 73.7898 |
| maps_url | (link you copied manually) |
| instagram_handle | (optional) |
| price_band | 1 to 4 |
| notes | known for sabudana khichdi |

**Sheet 2: dishes**

| Column | Example |
|---|---|
| dish_key | `ananda_sabudana_khichdi` |
| restaurant_key | `cafe_ananda_college_rd` |
| dish_name | Sabudana Khichdi |
| category | Breakfast |
| price_inr | 80 |
| is_veg | TRUE |
| photo_file | `ananda_sabudana_khichdi.jpg` |
| photo_credit_handle | `@yourhandle` |
| consent_method | self-shot / message / owner-permission |
| consent_date | 2026-11-02 |
| caption | (optional) |

(The coordinates above are illustrative; collect real ones.)

**Import script outline:** read both CSVs, normalise names with the same rules as `norm_name`, upsert restaurants, upsert dishes, resize and upload photos, insert posts with `source='seed'`, set each dish's `cover_post_id`. Run it locally with a service key that never ships in the app.

### Appendix F: Launch checklists

**Legal and policy**
- [ ] Privacy Policy live and linked in the app and the store listing
- [ ] Terms of Use with content licence
- [ ] Community Guidelines
- [ ] Grievance contact page
- [ ] Delete-account web page
- [ ] Sponsored-content rule visible
- [ ] Lawyer review done
- [ ] Name, domain and trademark check done

**Play Console**
- [ ] Developer account verified
- [ ] App created with the final package name
- [ ] Data safety form complete
- [ ] Content rating complete
- [ ] Target API level confirmed
- [ ] Closed test track created; at least 12 testers recruited (if required)
- [ ] Store listing, icon, feature graphic, screenshots
- [ ] Staged rollout configured

**Product**
- [ ] 150+ live dishes, 8+ categories, consent log complete
- [ ] Moderation drill done
- [ ] Backup restore drill done
- [ ] Crash-free rate 99%+ in testing
- [ ] Analytics dashboard ready

### Appendix G: Starter commands (verify against current docs)

```bash
# App
npx create-expo-app@latest worthabite
cd worthabite
npx expo install expo-router expo-image expo-location expo-image-picker \
  expo-image-manipulator expo-secure-store react-native-reanimated \
  react-native-gesture-handler
npm i @shopify/flash-list @tanstack/react-query zustand react-hook-form zod \
  @supabase/supabase-js react-native-mmkv posthog-react-native @sentry/react-native
# Build a development client rather than using Expo Go
npx expo prebuild --platform android   # or use EAS Build

# Backend
npm i -g supabase
supabase init
supabase link --project-ref <your-ref>
# put the SQL from Appendix A into supabase/migrations/0001_init.sql, then:
supabase db push
```

Package names and flags change. Follow each library's current installation guide before running.

### Appendix H: Sources

All findings come from the research report `Dish discovery app competitor landscape.md` (October 2026) and its notes. Key sources it cites:

**Competitors and market**
- FavHiker: https://www.favhiker.com/ ; Play Store https://play.google.com/store/apps/details?id=com.delnie.favhiker ; AppBrain https://www.appbrain.com/app/favhiker-discover-food-spots/com.delnie.favhiker ; App Store (India) https://apps.apple.com/in/app/favhiker-discover-food-spots/id6466083218
- Mapie: https://www.mapiefood.com/
- Swiggy Eatlists: https://www.businesstoday.in/technology/news/story/swiggy-launches-eatlists-feature-to-help-share-food-recommendations-with-friends-family-check-how-to-use-it-435978-2024-07-05 ; https://yourstory.com/2024/07/swiggy-adds-collaborative-food-recommendation-feature-eatlists
- Swiggy Bites (Medianama): https://www.medianama.com/2025/12/223-swiggys-short-video-feed-restaurant-visibility-dineout/
- Beli: https://en.wikipedia.org/wiki/Beli_(app) ; https://pitchbook.com/profiles/company/509955-85 ; https://apps.apple.com/us/app/beli/id1478375386
- Foodspotting: https://www.chefstemp.com/what-happened-to-foodspotting/ ; https://techcrunch.com/2013/01/29/foodspotting-ceo-alexa-andrzejewski-on-her-10-million-sale-to-opentable-video/ ; https://techcrunch.com/2012/11/30/foodspotting-rewards/
- Dish.fm: https://techcrunch.com/2012/12/17/dish-fm-relaunches-its-foodspotting-competitor-to-make-best-dish-recommendations-using-reviews-from-yelp-foursquare-and-instagram/ ; https://www.crunchbase.com/organization/dish-fm
- Forkly: https://techcrunch.com/2013/10/07/forkly-the-foodspotting-competitor-from-brightkite-founders-is-shutting-down
- Burrp: https://yourstory.com/2017/07/burrp-bookmyshow-acquisition-what-next
- Google popular dishes: https://techcrunch.com/2019/05/30/google-maps-now-uses-machine-learning-to-find-restaurants-best-dishes-make-suggestions
- Yelp popular dishes: https://engineeringblog.yelp.com/2019/10/discovering-popular-dishes-with-deep-learning.html
- Dishcision (single source, unverified): https://scihub101.com/blog/best-restaurant-and-dish-discovery-apps-2026

**Feed, UX and performance**
- Uber Eats video feed: https://techcrunch.com/2024/04/08/uber-eats-launches-tiktok-like-video-feed/amp/
- Google on young users and TikTok/Instagram: https://fortune.com/2024/09/10/gen-z-google-verb-social-media-instagram-tiktok-search-engine
- Shopify FlashList: https://shopify.engineering/instant-performance-upgrade-flatlist-flashlist
- Expo Image: https://docs.expo.dev/versions/latest/sdk/image/
- Wilson score: https://www.evanmiller.org/how-not-to-sort-by-average-rating.html

**Law and platform**
- Meta v. Bright Data: https://www.zyte.com/blog/california-court-meta-ruling/ ; https://www.courthousenews.com/federal-judge-rules-against-meta-in-data-scraping-case/
- Instagram hashtag search: https://developers.facebook.com/docs/instagram-platform/instagram-api-with-facebook-login/hashtag-search
- Instagram oEmbed: https://developers.facebook.com/docs/instagram-platform/oembed
- DPDP and scraping: https://consentos.in/learn/social-media-scraping-dpdp/ ; https://iapp.org/news/a/scraping-public-data-in-india-innovation-enabler-or-privacy-threat-
- IT Amendment Rules 2026: https://www.khaitanco.com/thought-leadership/MeitY-notifies-the-IT-Amendment-Rules-2026
- ASCI disclosure summary: https://www.sansalegal.com/post/asci-influencer-advertising-guidelines-2026-disclosure-rules-for-paid-content-and-ai-influencers
- Google Places policies: https://developers.google.com/maps/documentation/places/web-service/policies

---

*End of document.*
