# Putting WorthAbite on Google Play

Everything needed for the Play Console, ready to copy. The steps you do yourself are marked **You**.

## 1. The build

Every Android CI run now builds two files from the same code, version and release key:

| File | Where | For |
|---|---|---|
| `WorthAbite-<version>.apk` | GitHub release | Pilot testers (WhatsApp link). Updates itself from GitHub. |
| `WorthAbite-<version>-play.aab` | Run artifact **WorthAbite-play-aab** (Actions → the run → Artifacts) | Google Play only. |

The Play bundle (`-Pstore=play`) leaves out the GitHub updater and the "install unknown apps"
permission, because Play does not allow apps to update themselves. Play updates it instead.

**Signing.** Use **Play App Signing** (the default for new apps). The bundle is signed with the
WorthAbite release key, which becomes your *upload key*. Google keeps the key that signs what
phones download. Keep the release keystore and its password safe. Never commit or share them.

**Google Maps key.** The Maps key is restricted to the release key's SHA-1. Once Play re-signs the
app, add Play's **app signing key SHA-1** (Play Console → Test and release → App integrity) to the
same key in Google Cloud Console, or the Play version falls back to the MapLibre map.

Note: an app installed from the GitHub APK and one from Play are signed differently once Play
re-signs, so a phone can't switch from one to the other without uninstalling first. Tell pilot
testers to uninstall the GitHub version before joining the Play test.

## 2. Store listing (copy and paste)

**App name** (30 max): `WorthAbite: Best Dish Near You`

**Short description** (80 max):
`Craving pav bhaji? See the best one in your city, ranked by people who ate it.`

**Full description** (4,000 max):

```
Craving pav bhaji? WorthAbite tells you where to go.

Most apps rate the whole restaurant. But you don't eat a restaurant, you eat a dish. WorthAbite ranks one dish at one place, using votes from people who actually ate it.

HOW IT WORKS
• Type what you're craving: misal pav, vada pav, biryani, masala dosa, anything.
• See every place in your city that serves it, best first, with a plain line like "82% of 34 people who tried it loved it".
• Real photos from real diners, the price people actually paid, and tags like Jain, spicy or mild.
• Call the place or get directions in one tap.

RANKINGS YOU CAN TRUST
• Only people who tried a dish vote: "Loved it" or "Not for me". One person, one vote.
• No paid placement in rankings. Ever. Sponsored posts are always labelled and never appear in rankings.
• New accounts, accounts that vote in lockstep and whoever added a place can't push a dish up.
• People whose picks keep turning out right count a little more.

MADE FOR
• Students on a budget: filter under ₹100 or ₹200.
• Vegetarian and Jain diners: veg-only views never show meat.
• Newcomers and visitors who want the real local favourites.
• Anyone tired of reading 50 reviews to choose dinner.

POST IN 30 SECONDS
Snap your plate, pick the place, say if you loved it. Your photo helps the next hungry person, and your diary keeps every dish you've tried and what you paid.

Starting in Nashik, Maharashtra. More cities soon.
Browse without an account. Sign in only to vote, save or post. Adults (18+) only.
```

**App category:** Food & Drink. **Tags:** Restaurants, Food reviews, Local guide.

**Contact email:** your support address (the same one as `CONTACT_EMAIL` on the server).
**Website:** `https://<your server>/`. **Privacy policy:** `https://<your server>/privacy`.

**Graphics** (in `docs/play-store/`):

| Asset | File | Size |
|---|---|---|
| App icon | `docs/play-store/icon-512.png` | 512 × 512 |
| Feature graphic | `docs/play-store/feature-graphic-1024x500.png` | 1024 × 500 |
| Phone screenshots (2 to 8) | Pick from `docs/device/` (real emulator shots): home, craving list, dish page, map, post | 1080 × 2400 is fine |

Suggested screenshot order: 1) Home "What are you craving?", 2) a craving list ("Best Pav Bhaji"),
3) a dish page with "% loved", 4) the map, 5) posting a dish.

## 3. App content (Policy → App content)

**Privacy policy:** `https://<your server>/privacy`. **You:** fill in the bracketed parts first and have it reviewed.

**Account deletion:** in the app at Profile → Settings → Delete account, and on the web at
`https://<your server>/delete-account` (Play asks for this web link).

**Target audience and content:** 18 and over only. Not designed for children.

**Content rating questionnaire (IARC):** choose the category for apps with **social features and
user-generated content**. Answers: users can interact and share
content (yes); user-generated content is moderated (yes: report, block, review queue); shares
location (approximate location used for distance; not shared with other users); no violence,
gambling or purchases.

**Ads:** No ads (sponsored cards are not running yet; update this when they do).

**News app:** No. **Government app:** No. **Financial features:** None.

**User-generated content policy:** the app already has the required pieces: terms users accept
(18+, no objectionable content), report on every post, comment and profile, block, a moderation
queue for admins, and removal of reported content.

## 4. Data safety form (answers)

Data is encrypted in transit (HTTPS). Users can ask for deletion (in-app and web). No data is sold
or shared with third parties for advertising.

| Data type | Collected | Shared | Why | Optional? |
|---|---|---|---|---|
| Email address | Yes | No | Account management, sign-in codes | Required for an account |
| Name / username | Yes | No | Profile shown to others (handle, optional display name) | Required for an account |
| Photos | Yes | No | Dish photos and optional profile photo the user posts | Optional |
| Approximate location | Yes | No | Distances, "Nearby", sorting | Optional (permission) |
| Precise location | Yes | No | Verified-visit badge on a photo, in-app map | Optional (permission) |
| Messages (in-app) | Yes | No | Dish sharing between people who follow each other | Optional |
| Other user-generated content | Yes | No | Votes, comments, captions, prices, tags, hours | Optional |
| App interactions | Yes | No | Which dishes were opened or shown, to rank the feed | Collected when used |
| Device or other IDs | Yes | No | Push notification token (Firebase Cloud Messaging) and a device name on the sessions list | Optional (notifications) |

Not collected: contacts, financial info, health, calendar, audio, files, browsing history, crash
logs (none sent), advertising ID.

Firebase Cloud Messaging (Google) delivers notifications; it receives the push token only.
Google Maps (if the Maps key is set) loads map tiles; it does not receive account data.

## 5. Testing track: what Google requires before production

New personal developer accounts must run a **closed test with at least 12 testers who stay
opted in for at least 14 days in a row** before they can apply for production access.

**You:**

1. Play Console → Create app → name "WorthAbite", app, free, accept the declarations.
2. Fill **Store listing** (section 2), **App content** (section 3) and **Data safety** (section 4).
3. Testing → **Closed testing** → Create track → add testers by email list (a Google Group is
   easiest: create `worthabite-testers@googlegroups.com` and add people to it).
4. Upload the `.aab` from the latest CI run (artifact **WorthAbite-play-aab**) as a new release on that track.
   Release notes: copy from the GitHub release.
5. Send testers the opt-in link from the track page. Each tester must open it, tap **Become a
   tester**, then install from Play. Ask them to keep the app installed for 14 days.
6. Recruit **15 to 20** testers so that at least 12 stay for 14 days (some always drop out).
   Good sources: the 20 to 30 WhatsApp and college-club testers from the launch plan.
7. During the 14 days, ask testers to actually use it (search, vote, post). Google asks what you
   learned when you apply for production.
8. After 14 days: Dashboard → **Apply for production**, answer the questions (who tested, what
   feedback, what you changed), then promote the release to Production.

Review usually takes a few days. Keep the GitHub APK going for pilot users in the meantime.

## 6. Every later release

1. Release as usual (Actions → Android → Run workflow with a version and notes).
2. Download the `-play.aab` artifact from that run and upload it to the Play track.
   The version code is the CI run number, so it always goes up.
