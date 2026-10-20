# Publish wakemeup on GitHub

This guide documents the initial upload of the source project, README and banner from `D:\Projects\WakeMeUp`. Commands below are for Windows PowerShell. Initialization and the first remote setup are one-time steps; for an already connected checkout, use the Future updates section instead.

## 1. Your repository

Repository: [Gauravch-dev/wakeMeUp](https://github.com/Gauravch-dev/wakeMeUp)

HTTPS remote: `https://github.com/Gauravch-dev/wakeMeUp.git`

- Choose **Public** if you want recruiters to view it without an invitation.
- Leave **Add a README**, **Add .gitignore** and **Choose a license** unselected. The README and ignore rules are already prepared locally; choose the app's source license separately when ready.
- Suggested repository description:

  > A personal side project inspired by my own train commute: an Android alarm that wakes you one station before your destination. Kotlin, Jetpack Compose and offline routes.

The commands below already use your repository URL. These steps follow [GitHub's guide for uploading existing local code](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github).

If you already created a README or another commit on GitHub, the repository is not empty. Do not force-push over it. A straightforward alternative is to clone that repository into a separate folder, copy the source files listed in step 4 into the clone, and commit/push there instead.

## 2. Open PowerShell in the project

```powershell
Set-Location 'D:\Projects\WakeMeUp'
git --version
```

If Git is not installed, install [Git for Windows](https://git-scm.com/downloads/win), reopen PowerShell, and try again.

## 3. Initialize local Git and set your commit identity

```powershell
git init -b main
```

If you already ran this step, continue with the existing repository instead of creating another one.

Set the identity for this repository, replacing the values below. Use a verified GitHub email or the exact private `noreply` address shown in your GitHub email settings.

```powershell
git config user.name "YOUR NAME"
git config user.email "YOUR GITHUB COMMIT EMAIL"
```

These are repository-local settings; they do not change the identity used by unrelated projects.

## 4. Stage and review the files

```powershell
git add .
git status --short
git diff --cached --stat
```

The upload should include:

- `README.md`, `.gitignore` and `docs/`, including `docs/assets/hero.svg`.
- `app/src/`, containing the Kotlin source, tests, manifest, icons and fallback alarm.
- Root/app Gradle configuration, `gradle/`, `gradlew`, `gradlew.bat` and `build.ps1`.
- `data/`, including its attribution, generator and preserved station exports.

The upload should exclude:

- `local.properties`, which contains your machine's SDK path.
- `.tools/`, `.gradle/`, `.kotlin/`, `.idea/` and generated `build/` directories.
- APK/AAB outputs, signing keystores and local environment files.

The supplied `.gitignore` handles these exclusions. Keep `gradle/wrapper/gradle-wrapper.jar`: it is part of the build wrapper and should be committed.

If the staged list contains unexpected personal files, stop and remove them from staging before committing. No account credentials or API keys are needed to build this app.

## 5. Create the first commit

```powershell
git commit -m "Initial commit: wakemeup Android journey alarm"
```

## 6. Connect to your GitHub repository and push

Run these commands to connect this project to your repository:

```powershell
git remote add origin https://github.com/Gauravch-dev/wakeMeUp.git
git remote -v
git push -u origin main
```

Follow the GitHub authentication prompt if one appears. See [GitHub authentication options](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github) if sign-in is not configured. GitHub account passwords are not used for HTTPS Git authentication.

If `origin` already exists, inspect `git remote -v`. If it points to this intended repository, proceed to the push; if it is wrong, correct it with `git remote set-url origin https://github.com/Gauravch-dev/wakeMeUp.git`.

Refresh the repository page. Confirm that the README, banner and architecture diagram render and that the `app/`, `data/` and Gradle files are present.

## 7. Make the repository easy to assess

- Add the description above to the repository's **About** section.
- Suggested topics: `android`, `kotlin`, `jetpack-compose`, `location-based`, `offline-first`, `mumbai-local`, `train-alarm`, `foreground-service`.
- Pin the repository to your GitHub profile if it is a portfolio project.
- Add actual home, active-journey and alarm screenshots when available. The README banner is an illustration, not a fabricated application screenshot.
- Keep test and compatibility claims tied to what has actually been checked. The current README distinguishes local tests from device testing.

## 8. Optional: publish a downloadable test APK

Publish APKs as [GitHub Release assets](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github#distributing-large-binaries), rather than committing them to the source tree.

1. Build and verify with `.\build.ps1`.
2. On the repository page, open **Releases** and create a new release.
3. Use tag `v0.5.0` targeting the uploaded `main` branch, and a title such as `wakemeup v0.5.0 — Station-based wake-up alarms`.
4. Attach `app/build/outputs/apk/debug/app-debug.apk`. You can name the uploaded asset `wakemeup-v0.5.0-debug.apk`.
5. Describe it as a personal/testing build. Mention Android 8.0+, Google Play services, the required permissions, station-based wake-up behaviour and the need to test while awake.
6. Publish when you are ready and add its actual download link to the README.

Debug keys are computer-specific. Preserve the signing identity for future updates; a newly built APK with another signing key cannot update an existing installation signed with the old one. Keep signing secrets outside the repository. Source-code licensing can be chosen separately; retain the OpenStreetMap and Wikidata attributions in `data/README.md`.

## Future updates

After changing the project, review and commit the changes, then push:

```powershell
git add .
git diff --cached --stat
git commit -m "Describe the change"
git push
```

Update the version and README when behaviour changes, and publish a new Release asset when distributing a new APK.
