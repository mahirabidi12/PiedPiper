import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

import type { Account, Signal } from '../types';
import { logError, logStep } from '../utils/logger';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldPlaySound: true,
    shouldSetBadge: false,
    shouldShowBanner: true,
    shouldShowList: true,
  }),
});

let permissionsRequested = false;

export async function configureLocalNotifications() {
  if (Platform.OS === 'web' || permissionsRequested) return;

  permissionsRequested = true;
  try {
    const current = await Notifications.getPermissionsAsync();
    const finalStatus =
      current.status === 'granted'
        ? current.status
        : (await Notifications.requestPermissionsAsync()).status;

    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('mesh-events', {
        name: 'Offline mesh events',
        importance: Notifications.AndroidImportance.HIGH,
        vibrationPattern: [0, 250, 250, 250],
        lightColor: '#ff3b30',
      });
    }

    logStep('notifications', 'local notification permission result', { status: finalStatus });
  } catch (error) {
    logError('notifications', 'local notification setup failed', error);
  }
}

export function notifyIncomingSignal(account: Account | null, signal: Signal, sourceName: string) {
  if (account?.role !== 'admin') return;

  void scheduleLocalNotification(
    'New emergency packet',
    `${signal.senderName || sourceName}: ${signal.summary}`,
  );
}

export function notifyStatusUpdate(account: Account | null, signal: Signal) {
  if (account?.role !== 'user') return;

  void scheduleLocalNotification(
    'Responder updated your signal',
    `${signal.summary} is now ${signal.status.replace('_', ' ')}.`,
  );
}

async function scheduleLocalNotification(title: string, body: string) {
  try {
    await Notifications.scheduleNotificationAsync({
      content: {
        title,
        body,
        sound: true,
      },
      trigger: null,
    });
    logStep('notifications', 'local notification scheduled', { title });
  } catch (error) {
    logError('notifications', 'local notification failed', error);
  }
}
