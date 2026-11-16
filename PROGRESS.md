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

- ויקטורי/קרפור (v0.1.12) — נקראים **מהטלפון** (`data/LocalChains.kt`), כי האתרים חוסמים IP מחוץ לישראל: ויקטורי דרך `laibcatalog.co.il/webapi/api/getfiles?edi=…` (+ `/webapi/<edi>/<file>`), קרפור דרך `prices.carrefour.co.il` (`const path`/`const files` ב-HTML). סניפים — כולם; מחירים — רק לסניפים בעיר שנבחרה (עד 12 לרשת), פעם ב-20 שעות או בהחלפת עיר. סטטוס בהגדרות → מחירים. **לא נבדק מול השרתים (חסום מכאן)** — לאמת על הטלפון.
- שלב 5 ✅ (v0.1.12) — מתכונים: Room v4 (`recipes`, `ingredients`), רשימה בגריד + חיפוש + צ'יפים, דף מתכון (גלריה בהחלקה, מרכיבים עם עלות מהמוצר, עלות כוללת וליחידה, הערות, מחיקה), עורך (תמונות מה-Photo Picker → webp ≤1600px ב-filesDir, תמונה ראשית, מרכיב ↔ מוצר או טקסט חופשי, כמות במקלדת, אייקון, הערות). מחיקת מוצר מנתקת מרכיבים (השם נשמר).
  - חסר: סידור מחדש של מרכיבים (גרירה) — לשלב 6.

- שלב 6 ✅ (v0.1.13) — כמויות ותמחיר: Room v5 (`recipes.extras` JSON, `salePrice` אגורות ליחידה, `targetMargin` %). בדף מתכון: "כמויות" (צעד יחידות ±1 + חצי/רגיל/כפול/פי 3, לא נשמר) → כמויות ועלות חיות; כרטיס תמחיר → דף `CostingPage`: עלויות נוספות (₪ לתבנית / ₪ ליחידה / % מהמרכיבים, תבניות: אריזה, גז/חשמל, עבודה, קישוט), מחיר מכירה → רווח ומרווח, או מרווח יעד → מחיר מומלץ ("זה המחיר שלי"). מרווח = רווח/מחיר.
  - כפתור + מרכזי: מיושר בדיוק, + מצויר (לא גליף).
  - הגדרות → מחירים: כרטיס "רשתות ב<עיר>" — לכל 6 הרשתות מספר סניפים בעיר / "אין סניף בעיר" / "לא הצלחנו לקרוא" + "לנסות שוב". ויקטורי נכשל בטלפון (סיבה מוצגת בשורה הקטנה) — לבדוק לפי מה שמופיע.
  - חסר: סידור מחדש של מרכיבים (גרירה).

- מחירון (v0.1.15): ה-Action היומי כותב `catalog.tsv.gz` (כל מוצר ארוז מכל הרשתות: ברקוד, שם, מותג, גודל, יחידה, מס' סניפים; ~58k, ‏1MB) ומעלה ל-release קבוע `catalog` (prerelease, לא משפיע על "בדוק עדכונים"). האפליקציה מורידה פעם בשבוע ל-Room v6 (`catalog`). "חיבור למחירי הרשתות" בעורך מוצר + "חיפוש במחירון" בדף מוצר בלי ברקוד → קובע ברקוד, watch, וסנכרון מיידי (ויקטורי/קרפור מיד, השאר מחר). ברקוד = אופציונלי.

- שלב 7 (בתהליך) — ענן:
  - החלטות: משתמשת אחת/כמה מכשירים; התחברות **אופציונלית** (הגדרות → גיבוי וסנכרון).
  - בלי Firebase SDK: Google דרך Credential Manager → Firebase Auth REST (`signInWithIdp`, refresh ב-securetoken), Firestore/Storage ב-REST (`data/Cloud.kt`).
  - Room v7: `gid` (UUID) + `dirty` ב-products/recipes, טבלת `tombstones`. Room = מקור האמת; ענן = `users/{uid}/{products|recipes}/{gid}` עם `syncAt` (זמן שרת). משיכה לפי watermark, עריכה מקומית שלא נשלחה מנצחת, מוצרים עם אותו ברקוד מתמזגים. תמונות מתכון → Storage `users/{uid}/photos/<file>.webp`.
  - טריגרים: פתיחת אפליקציה, 2.5 שנ' אחרי שינוי מקומי, "לסנכרן עכשיו". בהתחברות — הכל מסומן לשליחה (מיזוג).
  - `.github/workflows/firebase.yml` + `scripts/firebase/setup.py`: מפעיל APIs, רושם SHA-1/256 של ה-keystore, יוצר bucket (`US-EAST1`), מפרסם firestore+storage rules. ה-build קורא `GOOGLE_WEB_CLIENT_ID`/`STORAGE_BUCKET` (`--read-only`). נבדק: bucket נוצר, SHA נרשמו, rules פורסמו.
  - ספק Google הופעל (2026-11-16), v0.1.17 נבנתה עם `GOOGLE_WEB_CLIENT_ID`; ה-API key מורשה ל-identitytoolkit/securetoken (נבדק). **לא נבדק בטלפון.**
  - v0.1.18: תמונות מוצר נשמרות בטלפון כ-webp ≤800px (`filesDir/products`, `ProductPhotos`) — הורדה מ-OFF בשמירה/בפתיחה, סנכרון ל-Storage באותה תיקייה (`image` = שם קובץ; `imageUrl` רק ל-http ישן). אחרי push: תמונות שהעלינו ושאף מתכון/מוצר לא משתמש בהן נמחקות מ-Storage. **לא נבדק בטלפון.**

## הבא
- שלב 7 — נשאר רק אימות בטלפון: התחברות, סנכרון בין שני מכשירים, תמונות. אחרי אישור → ✅ ומעבר לשלב 8 (רשימת קניות).
- אימות ויקטורי/קרפור על הטלפון לפי שורת הסטטוס בהגדרות. העברת תמונות ל-Storage הועברה לשלב 7.
- עלויות: Actions חינם (repo ציבורי), Firestore עשרות כתיבות ביום (בתוך החינמי).
