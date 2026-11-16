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
- keystore: הבעלים בלי מחשב → ה-CI ייצר keystore בפעם הראשונה וישמור אותו פרטי בפרויקט Firebase (Secret Manager/Storage), ומשם יקרא בכל build. לא צריך סודות נוספים.
- סביבת ענן `Cookies-By-Maites`: גישה ל-Open Food Facts ולאתרי המחירים.

ישן: `FIREBASE_TOKEN`, `GOOGLE_SERVICES_JSON`, `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

## הבא
- להשלים אב-טיפוס ולקבל הערות.
