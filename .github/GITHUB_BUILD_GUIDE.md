# How to get your APK (free, no Android Studio needed)

## Step 1 — Create a free GitHub account
Go to https://github.com and sign up (it's free).

## Step 2 — Create a new repository
1. Click the **+** button (top-right) → **New repository**
2. Name it: `FreeConnectForBedrock`
3. Set it to **Private** (so only you can see it)
4. Click **Create repository**

## Step 3 — Upload the project files
On the next page click **uploading an existing file**, then:
1. Extract the `.tar.gz` you downloaded from Replit
2. Drag the entire `FreeConnectForBedrock` folder contents into the upload box
3. Scroll down and click **Commit changes**

## Step 4 — Watch it build automatically
1. Click the **Actions** tab at the top of your repository
2. You'll see a workflow called **Build Debug APK** already running
3. Wait ~3-5 minutes for it to finish (green checkmark = done)

## Step 5 — Download your APK
1. Click on the finished workflow run
2. Scroll down to the **Artifacts** section
3. Click **FreeConnect-debug-APK** to download a `.zip`
4. Extract it — inside is `app-debug.apk`

## Step 6 — Install on your phone
1. Transfer the APK to your Android phone (AirDrop, Google Drive, USB, email)
2. On your phone: **Settings → Security → Install unknown apps** → allow your browser/file manager
3. Tap the APK file to install

---

> The build runs on GitHub's free servers. You get 2,000 free build-minutes per month — more than enough.
