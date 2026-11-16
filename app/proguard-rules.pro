# Room entities / JSON parsing via org.json — nothing reflective yet.

# Credential Manager loads its Play Services provider reflectively.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
