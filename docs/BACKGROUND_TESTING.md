# Background monitoring regression checks

Version 0.5.3 saves active journey instructions and returns `START_STICKY` while monitoring is active. This lets Android attempt to recreate the foreground service after process death. It does not bypass force-stop, reboot or manufacturer restrictions. Pure unit tests exercise snapshot validation and fresh-location recovery; the following checks exercise the actual Android lifecycle.

## Phone preparation

Install the updated APK over the existing app. On the OnePlus Nord CE5, open **wakemeup → info → App settings**, then inspect its battery/background-activity controls. Allow background activity where available. The separate **Battery settings** shortcut opens Android's battery-optimization list; choose wakemeup and disable optimization if desired. OxygenOS labels and menus can vary. Battery Saver being off does not establish that an app has unrestricted background activity.

Allow precise location and notifications, enable device location, keep alarm volume audible and test the sound. Do not force-stop the app during normal journey testing.

## Locked-screen journeys

Test while awake. For each direction, select the actual source and destination, verify the route and start monitoring. Confirm tracking before locking the screen. Check the journey notification occasionally without ending the journey.

| Journey | Expected wake-up station |
| --- | --- |
| Panvel → Vashi | Sanpada |
| Vashi → Panvel | Khandeshwar |

If monitoring recovers after an interruption, the app initially shows that location is being checked. It must not show an old position as current. Two fresh, consistent readings restore tracking; two readings at the wake-up point confirm a station alarm. GPS-loss and recovery warnings must remain silent. A station alarm already confirmed before process death remains latched until acknowledged.

## Controlled process death on a development device

This requires USB debugging and the debug APK. It deliberately kills the app process during a test journey. Keep the user awake and do not use this during a journey on which they depend for an alarm.

1. Start a journey in the visible app, confirm the notification and move the app to the background.
2. In PowerShell with `adb` available, run:

   ```powershell
   $journeyPid = (& adb shell pidof -s com.laststop.app).Trim()
   if ($journeyPid -notmatch '^\d+$') { throw 'wakemeup is not running' }
   adb shell run-as com.laststop.app kill -9 $journeyPid
   ```

3. Allow Android time to recreate the sticky service. Recovery timing is system-controlled. Verify the notification returns, the source/destination/leg/sound are unchanged, and tracking waits for fresh readings. Repeat once more.
4. Reopen the app. A valid interrupted journey can recover here if Android did not restart it in the background. **Info → Monitoring details** should show the interruption/recovery event and Android's available exit reason. Copy the report if investigating a failure.
5. End the journey explicitly. Verify the notification disappears and monitoring does not restart when reopening the app. No recovery instructions should remain.

Do not use `am force-stop` or Android's Active apps **Stop** control as the simulated memory/process kill: those intentionally suppress normal background recovery. Test those separately and confirm the app does not promise ongoing monitoring afterward. Reboot and expiry also invalidate saved recovery instructions. The six-hour limit is measured from the original leg start, including time across service restarts.

Android may report a native crash, app crash, memory pressure, signal termination, user stop or another cause. Some manufacturer shutdowns have no precise record. An unavailable reason must remain labelled unavailable rather than being attributed to GPS or battery management without evidence.

References: [Android sticky-service restart semantics](https://developer.android.com/reference/android/app/Service#START_STICKY), [system Stop control](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping), [process-exit records](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)), [battery optimization](https://developer.android.com/training/monitoring-device-state/doze-standby).
