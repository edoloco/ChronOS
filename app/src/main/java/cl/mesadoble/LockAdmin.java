package cl.mesadoble;

import android.app.admin.DeviceAdminReceiver;

/** Solo pide "bloquear la pantalla": así el extendido se bloquea cuando el principal se bloquea. */
public class LockAdmin extends DeviceAdminReceiver { }
