# SelfPilot

**Tagline**: Take Control. Stay Focused. Build Discipline.

## Project Introduction
SelfPilot is an Android Digital Wellbeing and Productivity application designed to help users reduce unnecessary phone usage, improve study habits, and develop digital discipline. Through a gamified Honor Score system, focus timers, and smart app restrictions, users can balance their digital lives efficiently.

## Objectives
- Track screen time and categorise application usage (Study vs Non-study).
- Build discipline using a point-based Honor Score.
- Prevent distraction through an Accessibility-powered Smart App Lock and Emergency Unlock system.
- Boost productivity using a Pomodoro Focus Timer.

## Feature List
- **Home Dashboard**: View total screen time, Discipline Score, and Study Points.
- **App Discovery**: View installed apps, track their usage, and categorize them as Study or Non-Study.
- **Honor Score & Gamification**: Daily score calculation based on usage habits (e.g. distracting app usage lowers score, study app usage increases points).
- **Focus Timer**: Built-in Pomodoro timer to track deep work sessions.
- **Smart App Lock**: Uses Android AccessibilityServices to block distracting apps.
- **Emergency Unlock**: Allows limited (3 times/day) access to blocked apps in emergencies (15 min per unlock).
- **Settings & Permissions**: Manage required permissions like Usage Access and Accessibility gracefully.

## Technology Stack
- **Language**: Java
- **UI**: XML Layouts, Material Design Components
- **Architecture**: MVVM
- **Local Database**: Room Database
- **System APIs**: `UsageStatsManager`, `AccessibilityService`, `PackageManager`
- **Background Execution**: `Service` (for timer), `Executors` (for Room operations)

## Package Structure
- `com.example.selfpilot.data`: Room Database, Entities, and DAOs.
- `com.example.selfpilot.ui`: Fragments and Activities organized by feature (home, apps, focus, score, block, settings).
- `com.example.selfpilot.service`: Background and system services (AccessibilityService, TimerService).
- `com.example.selfpilot.util`: Helpers for calculating scores, formatting time, and checking permissions.

## Setup & Build Instructions
1. Clone or copy the project into Android Studio.
2. Sync the project with Gradle files.
3. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install on a device/emulator:
   ```bash
   ./gradlew installDebug
   ```

## Testing Instructions
- Launch the application and accept the **Usage Access** permission.
- Navigate to the **Apps** tab to categorize apps as Study/Non-study.
- Navigate to the **Settings** tab to enable the **Accessibility Service**.
- Try opening a blocked app to trigger the Block UI and test Emergency Unlocks.
- Use the **Focus** tab to run a timer session.

## Known Limitations
- Background task execution and `AccessibilityService` stability vary depending on OEM battery-saving restrictions (e.g., MIUI, ColorOS).
- The App Freezer feature behaves as a blocker rather than a true force-stop due to Android system restrictions on third-party apps interacting with other app's lifecycles.
- UsageStats can sometimes have a few minutes of delay based on internal Android aggregation cycles.

## Future Improvements
- Cloud backup with Firebase.
- Customizable rules per app (time limits instead of hard block).
- Enhanced analytics with charts.