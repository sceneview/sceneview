import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  type AppStateStatus,
  Linking,
  Platform,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import DeviceInfo from 'react-native-device-info';
import SpInAppUpdates, {
  IAUUpdateKind,
  type StartUpdateOptions,
} from 'sp-react-native-in-app-updates';

/**
 * Cross-platform update checker for the SceneView React Native demo.
 *
 * **Android** — `sp-react-native-in-app-updates` wraps Google Play's In-App
 * Updates SDK. We pick the flexible flow so the download happens in the
 * background and the user can keep using the app, then "Restart" applies it.
 *
 * **iOS** — Apple does not expose an install API, so we hit
 * `https://itunes.apple.com/lookup?bundleId=<id>`, compare with
 * `DeviceInfo.getVersion()`, and bounce to `itms-apps://` if a newer build
 * is on the store.
 *
 * **When to mount.** Drop `<UpdateChecker />` once at the root of the app —
 * it self-throttles to one network round-trip per 12 h and listens to
 * `AppState` so a resume re-runs the check. No-op on web / unsupported
 * platforms.
 *
 * **Limits.** Snooze state lives in memory only — a process restart resets
 * it. Good enough for a demo; production apps should persist via
 * AsyncStorage / MMKV. The 12 h throttle is also in-memory.
 */
export function UpdateChecker(): React.ReactElement | null {
  const [available, setAvailable] = useState<string | null>(null);
  const lastCheck = useRef<number>(0);
  // Version-keyed snooze: the banner stays hidden while
  // `available === snoozedVersion`. A NEW `available` (= newer release since
  // the dismiss) re-surfaces the banner — matches the web demo semantics.
  const snoozedVersion = useRef<string | null>(null);
  const updaterRef = useRef<SpInAppUpdates | null>(null);

  const THROTTLE_MS = 12 * 60 * 60 * 1000;
  // App Store track id for the canonical Swift SceneView demo. The RN demo is
  // dev-only (template, not published), so when iOS users tap Update we
  // redirect them to the published 3D & AR Explorer rather than a 404 store
  // page. Swap to this demo's own track id once it ships to the App Store.
  const APP_STORE_ID = '6761329763';

  const checkForUpdate = useCallback(
    async (force = false) => {
      const now = Date.now();
      if (!force && now - lastCheck.current < THROTTLE_MS) return;

      try {
        if (Platform.OS === 'android') {
          if (!updaterRef.current) {
            updaterRef.current = new SpInAppUpdates(__DEV__);
          }
          const result = await updaterRef.current.checkNeedsUpdate();
          if (result.shouldUpdate) {
            setAvailable(result.storeVersion ?? 'available');
          } else {
            setAvailable(null);
          }
        } else if (Platform.OS === 'ios') {
          const bundleId = DeviceInfo.getBundleId();
          const current = DeviceInfo.getVersion();
          const response = await fetch(
            `https://itunes.apple.com/lookup?bundleId=${encodeURIComponent(bundleId)}`,
          );
          if (!response.ok) return;
          const json = (await response.json()) as {
            results?: { version?: string }[];
          };
          const latest = json.results?.[0]?.version;
          if (!latest) return;
          if (isNewer(latest, current)) {
            setAvailable(latest);
          } else {
            setAvailable(null);
          }
        }
        // Stamp `lastCheck` only on success so a transient failure (iTunes
        // 503, Play Core hiccup) doesn't burn the 12 h budget for the next
        // resume.
        lastCheck.current = now;
      } catch (err) {
        // Silent — see header. The banner stays hidden, the user keeps using
        // the demo. We log so a curious dev can see why.
        // eslint-disable-next-line no-console
        console.log('UpdateChecker failed', err);
      }
    },
    [THROTTLE_MS],
  );

  const startUpdate = useCallback(async () => {
    if (Platform.OS === 'android') {
      if (!updaterRef.current) return;
      const options: StartUpdateOptions = {
        updateType: IAUUpdateKind.FLEXIBLE,
      };
      try {
        await updaterRef.current.startUpdate(options);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.log('startUpdate failed', err);
      }
    } else if (Platform.OS === 'ios') {
      const url = `itms-apps://itunes.apple.com/app/id${APP_STORE_ID}`;
      const supported = await Linking.canOpenURL(url);
      if (supported) Linking.openURL(url);
    }
  }, []);

  const snooze = useCallback(() => {
    // Version-keyed snooze: a future `setAvailable('4.5.0')` will re-show
    // the banner because `available !== snoozedVersion`.
    snoozedVersion.current = available;
    setAvailable(null);
  }, [available]);

  useEffect(() => {
    // Initial check at mount.
    checkForUpdate(true);

    const handleAppStateChange = (state: AppStateStatus) => {
      if (state === 'active') {
        checkForUpdate(false);
      }
    };
    const sub = AppState.addEventListener('change', handleAppStateChange);
    return () => sub.remove();
  }, [checkForUpdate]);

  // Hide while snoozed for THIS specific version. Newer releases set
  // `available` to a new string, which won't match `snoozedVersion`.
  if (!available || available === snoozedVersion.current) return null;

  return (
    <View style={styles.banner} accessibilityLiveRegion="polite">
      <View style={styles.body}>
        <Text style={styles.title}>Update available</Text>
        <Text style={styles.subtitle}>
          {available === 'available'
            ? 'A newer build is ready.'
            : `Version ${available} is ready to install.`}
        </Text>
      </View>
      <TouchableOpacity style={styles.cta} onPress={startUpdate} activeOpacity={0.85}>
        <Text style={styles.ctaLabel}>Update</Text>
      </TouchableOpacity>
      <TouchableOpacity
        style={styles.dismiss}
        onPress={snooze}
        accessibilityLabel="Dismiss until next release"
      >
        <Text style={styles.dismissLabel}>×</Text>
      </TouchableOpacity>
    </View>
  );
}

/** Component-wise version compare (`1.2.10` > `1.2.9`). */
function isNewer(latest: string, current: string): boolean {
  const la = latest.split('.').map((s) => parseInt(s, 10) || 0);
  const lc = current.split('.').map((s) => parseInt(s, 10) || 0);
  const len = Math.max(la.length, lc.length);
  for (let i = 0; i < len; i++) {
    const x = la[i] || 0;
    const y = lc[i] || 0;
    if (x > y) return true;
    if (x < y) return false;
  }
  return false;
}

// Height of the App header (paddingVertical 14 + title ~18 + subtitle ~12 +
// hairline border). The banner is absolutely positioned within the
// `SafeAreaView` content area — which already excludes the status bar — so a
// fixed offset of HEADER_HEIGHT drops it cleanly below the header instead of
// overlapping the "SceneView / React Native Demo" title. `react-native-safe-
// area-context` is not a dependency of this demo, so a measured constant is
// the minimal fix here (vs. pulling in a new package for one inset).
const HEADER_HEIGHT = 58;

const styles = StyleSheet.create({
  banner: {
    position: 'absolute',
    top: HEADER_HEIGHT,
    left: 12,
    right: 12,
    marginTop: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1B2233',
    borderRadius: 22,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.35,
    shadowRadius: 14,
    elevation: 6,
    zIndex: 50,
  },
  body: { flex: 1, marginRight: 8 },
  title: { color: '#F3F4F6', fontWeight: '600', fontSize: 14 },
  subtitle: { color: '#9CA3AF', fontSize: 12, marginTop: 2 },
  cta: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 18,
    backgroundColor: '#005BC1',
  },
  ctaLabel: { color: 'white', fontWeight: '600', fontSize: 13 },
  dismiss: {
    width: 28,
    height: 28,
    marginLeft: 6,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dismissLabel: { color: '#9CA3AF', fontSize: 18, lineHeight: 18 },
});
