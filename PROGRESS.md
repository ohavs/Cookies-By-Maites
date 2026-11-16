# PROGRESS

## מצב
- 2026-11-16: אפיון הושלם (`docs/SPEC.md`), כולל התראות ירידת מחיר/מיקום/זמנים.
- שלב 1 — אב-טיפוס `web/proto/index.html`, חי כ-Artifact: https://claude.ai/artifact/GeLJvzbdMmvtuMgBYhD9YA — גרסה 2 (שפת עיצוב חדשה בהשראת Payments, Rubik, תמונות אמיתיות, קטגוריות + חיפוש, לשונית קניות). ממתין להערות.

## החלטות
- אנדרואיד native (Kotlin/Compose), שפת העיצוב של noted, accent לבחירה, Karantina + Heebo.
- ניווט: שתי לשוניות — מתכונים | מוצרים.
- מחירים: GitHub Action יומי מקבצי שקיפות המחירים → Firestore. רשתות: שופרסל, רמי לוי, ויקטורי, יוחננוף, אושר עד, קרפור.
- Firebase + Storage (Blaze).

## סודות (שמות בלבד)
- מוגדרים: `FIREBASE_TOKEN`, `GOOGLE_SERVICES_JSON` (Blaze פעיל).
- keystore (2026-11-16): נוצר בסשן ונשלח לבעלים כקבצים (גיבוי ב-Drive). סודות: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (=maites), `ANDROID_KEY_PASSWORD`. הוחלפו ע"י הבעלים, הבנייה החתומה עובדת.
- סביבת ענן `Cookies-By-Maites`: גישה ל-Open Food Facts ולאתרי המחירים.


- שלב 2 — שלד Kotlin נבנה ומתקמפל ב-CI (`.github/workflows/android.yml`): מעטפת + סרגל תחתון עם `+`, טוקנים מהאב-טיפוס (Rubik, 4 accent, בהיר/כהה), Room (products), DataStore, "בדוק עדכונים" (GitHub latest release → הורדה → התקנה, כולל הרשאת מקורות לא ידועים).
- גרסאות: tag `v0.1.<run_number>`, versionCode = run_number. אין main — ה-CI רץ על `ccr-*` ו-main. לינק קבוע: https://github.com/ohavs/cookies-by-maites/releases/latest/download/cookies-by-maites.apk

- שלב 3 — מוצרים (v0.1.5): רשימה בגריד לפי קטגוריה + חיפוש (שם/מותג/ברקוד) + צ'יפים (הכל/מועדפים/קטגוריות), דף מוצר (מועדף, מחיר ידני במקלדת משלנו, מחיר ל־100 גרם, עריכה, מחיקה עם אישור), עורך מוצר, סריקה (ML Kit code scanner, בלי הרשאת מצלמה) + מילוי אוטומטי מ-Open Food Facts. דפים נדחפים עם החלקה מהקצה (`ui/PageHost.kt`). Room v2 עם מיגרציה.
- תמונות מוצר כרגע: URL ישיר של OFF דרך Coil (מטמון דיסק). ההעברה ל-Storage/webp — בשלב 4 יחד עם ה-Action.

- שלב 4 ✅ — מחירים:
  - `scripts/prices/fetch.py` (stdlib) + `.github/workflows/prices.yml` יומי 03:17 UTC: קורא `watch/*` מ-Firestore, מוריד PriceFull של כל הסניפים, כותב `prices/{barcode}` {name, at, p:{chain_store: agorot}} ו-`stores/{chain}` {name, s:{id:{n,c,a}}}. רשת שנכשלה שומרת מחירי אתמול. ברקוד = בלי אפסים מובילים.
  - כתיבה ל-Firestore עם `FIREBASE_TOKEN` (החלפה ל-access token). ה-Action מפעיל Firestore API, יוצר DB (me-west1) ומפרסם `firestore.rules`.
  - ערים: `scripts/prices/cities.json` (קוד למ"ס → שם, מ-data.gov.il).
  - אפליקציה: Room v3 (`chain_stores`, `chain_prices`), `PriceSync` ב-REST עם API key (BuildConfig מ-`GOOGLE_SERVICES_JSON` ב-CI), סנכרון בפתיחה (כל 3 שעות), רישום ברקוד ל-`watch` בשמירת מוצר. עיר ב-DataStore, בורר עיר (גיליון), "מחירים ברשתות" בדף המוצר, "מ־₪X" בכרטיס מוצר.
  - רשתות פעילות: שופרסל, רמי לוי (`ramilevy`), יוחננוף, אושר עד. השלוש האחרונות דרך `url.publishedprices.co.il` (Cerberus): התחברות עם משתמש ציבורי בלי סיסמה (`RamiLevi`, `yohananof`, `osherad`), רשימה ב-`/file/json/dir`, הורדה ב-`/file/d/<name>`. Stores של Cerberus ב-UTF-16. נבדק ב-Action (2026-11-16): 4 רשתות ok, 309 סניפים לברקוד בדיקה. ויקטורי (laibcatalog) וקרפור — timeout גם מ-GitHub Actions (כנראה חסימה לכתובות מחוץ לישראל).
  - נבדק: Action ירוק, Firestore חי (פרויקט `cookies-by-maites`), rules נבדקו (watch תקין=200, זבל=403, כתיבה ל-prices=403), ברקוד בדיקה 7290000190853 נמשך מ-421 סניפים. שוחרר v0.1.8.

## הבא
- שלב 5 — מתכונים (`docs/ROADMAP.md`). לקרוא את הסעיף הרלוונטי ב-`docs/SPEC.md`.
- ויקטורי/קרפור: נדחו (חסימת IP זר). העברת תמונות ל-Storage הועברה לשלב 7.
- עלויות: Actions חינם (repo ציבורי), Firestore עשרות כתיבות ביום (בתוך החינמי).
