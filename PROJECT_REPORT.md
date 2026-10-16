# SelfPilot - Project Report

## 1. Project Abstract
SelfPilot addresses the growing concern of digital addiction and procrastination among students. This project develops a native Android application that bridges the gap between passive screen time tracking and active restriction mechanisms. By implementing an Honor Score system, Pomodoro timers, and intelligent accessibility-based app blocking, SelfPilot encourages healthy device usage habits.

## 2. Problem Statement
Smartphones are indispensable yet highly distracting. Standard digital wellbeing apps often provide only analytics without active enforcement, or rigid locks without flexible emergency options. There is a need for an app that gamifies focus, categorizes usage correctly, and applies restrictive measures intelligently.

## 3. Objectives
1. Implement a complete Android application using Java.
2. Read system usage statistics to differentiate between productive and unproductive screen time.
3. Use background services and accessibility events to enforce usage rules.
4. Persist historical and relational data locally using Room Database.

## 4. Functional Requirements
- The user must be able to view their daily screen time.
- The user must be able to classify installed apps.
- The app must track focus sessions.
- The app must block designated applications.
- The app must allow a maximum of 3 emergency unlocks per day.

## 5. Non-Functional Requirements
- **Performance**: The app should not cause battery drain. Database queries must happen off the main thread.
- **Privacy**: All usage data must remain strictly on the device.
- **Usability**: UI must adhere to Material Design principles.

## 6. Use Case Descriptions
- **UC1 (Categorize App)**: User navigates to Apps list, toggles "Study App" switch. App updates Room database.
- **UC2 (Start Focus Timer)**: User selects 25 mins, clicks Start. Service counts down and broadcasts UI updates.
- **UC3 (Emergency Unlock)**: User attempts to open a blocked app. BlockActivity appears. User selects "Emergency Unlock". App allows 15 minutes of access.

## 7. Database Schema
- **AppUsageEntity**: id (PK), packageName, date, totalTimeInForeground, isStudyApp
- **AppInfoEntity**: packageName (PK), appName, isStudyApp
- **HonorScoreEntity**: id (PK), date, studyPoints, disciplineScore
- **FocusSessionEntity**: id (PK), date, durationMinutes
- **RestrictionRuleEntity**: id (PK), packageName, limitMinutes, isBlocked, unlockedUntil
- **EmergencyUnlockEntity**: id (PK), date, unlocksUsed

## 8. Application Workflow
1. Launch -> Check `Usage Stats` permission -> Redirect or Proceed.
2. Main Dashboard -> Fetch local Room DB and aggregate system stats -> Display Score.
3. Accessibility -> Triggered on window change -> Check rule -> Launch Block Activity.

## 9. Testing Report
- **Unit Testing**: Score calculator logic verified to increment and decrement accurately.
- **Integration Testing**: Room database successfully performs CRUD on background threads.
- **System Testing**: Accessibility Service intercepts targeted apps consistently. UsageStats Manager retrieves accurate session data.

## 10. Future Scope
- Integration with external calendar APIs for Exam Mode synchronization.
- Peer-to-peer study group integration to sync Focus Timers.

---

# Advanced Programming Laboratory Mapping
| Topic | Implementation in SelfPilot |
|-------|-----------------------------|
| Android Studio & Gradle | Standard IDE setup with modern Gradle configuration. |
| Activities & Lifecycle | Handled across MainActivity, PermissionActivity, and BlockActivity. |
| UI & Layouts | Complex XMLs using ConstraintLayout, MaterialCardView, RecyclerView. |
| RecyclerView & Adapters | `AppListAdapter` loading dynamic packages and icons. |
| Services | `FocusTimerService` and `AppLockAccessibilityService`. |
| Broadcast Receivers | Used to communicate between the timer service and fragment. |
| Database Operations | Comprehensive implementation using Room Architecture components. |
| Background Tasks | ExecutorService used throughout for non-blocking UI. |
| Advanced System APIs | Integration of `UsageStatsManager` and `AccessibilityService`. |

---

# Presentation Outline
1. **Introduction**: The digital distraction problem.
2. **Solution**: Overview of SelfPilot and its gamified approach.
3. **Core Features Demo**: Dashboard, Apps list, Focus Timer.
4. **Technical Implementation**: Architecture, Room DB schema, Accessibility Service behavior.
5. **Challenges & Limitations**: Handling OEM background restrictions, system-app filtering.
6. **Conclusion & Future Vision**: Making the tool more intelligent.
