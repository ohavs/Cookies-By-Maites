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

## הבא
- שלב 4: משיכת מחירים (Action יומי → Firestore), בחירת עיר, מסך השוואה בדף המוצר; העברת תמונות ל-Storage.
