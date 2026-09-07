import type { CapacitorConfig } from '@capacitor/cli';
// STAR_ANDROID_DEV is only for USB-connected local debugging.
const dev = process.env.STAR_ANDROID_DEV === 'true';
const config: CapacitorConfig = {
  appId: 'com.starexplorer.app',
  appName: '星星探险家',
  webDir: 'dist',
  backgroundColor: '#fbf8eb',
  android: { backgroundColor: '#fbf8eb', allowMixedContent: dev },
  server: { androidScheme: dev ? 'http' : 'https', cleartext: dev },
};
export default config;
