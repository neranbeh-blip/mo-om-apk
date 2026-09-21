# MoPay upload and build

1. Upload the contents of this folder to `neranbeh-blip/mo-om-apk` (not the outer ZIP).
2. GitHub: Settings → Secrets and variables → Actions → New repository secret.
3. Add `NEXT_PUBLIC_SUPABASE_URL` with the new Supabase URL.
4. Add `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY` with the new publishable key.
5. Actions → Build MoPay APKs → Run workflow.
6. Download the `MoPay-APKs` artifact.

Never commit MTN/Orange API secrets, Supabase service-role keys, database passwords, or private signing keys. Operator secrets belong server-side in Supabase Edge Functions.
