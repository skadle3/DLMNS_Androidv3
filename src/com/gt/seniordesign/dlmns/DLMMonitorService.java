package com.gt.seniordesign.dlmns;

import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.app.AlarmManager;
import java.util.ArrayList;
import java.util.Scanner;
import java.io.File;
import java.io.FileWriter;
import java.lang.Thread;

public class DLMMonitorService extends Service {

	private final IBinder mBinder = new LocalBinder();
	public static ArrayList<KnownDevice> monitoredDevices;
	private ArrayList<PendingIntentInfo> pendingIntents;
	private AlarmManager am;
	private long lastEntry  = 0;
	private BluetoothAdapter bluetoothAdapter;
	
	private class PendingIntentInfo {
		int requestCode;
		int deviceHash;
		PendingIntent intent;
		
		public PendingIntentInfo(int rc, int dh, PendingIntent pi) {
			this.requestCode = rc;
			this.deviceHash = dh;
			this.intent = pi;
		}
	}

	private class MyBleCallback extends BluetoothGattCallback  {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			Log.i("DLMNS_Service", "Connection State Chnge - New State: " + newState + " Status: " + status);
			KnownDevice myDevice = getKnownDevice(gatt.getDevice().hashCode());
			if (newState == 2 && status == 0) {

				myDevice.connectionStateCallBackCalled = true;

				try {
					gatt.discoverServices();
				} catch (Exception ex) {
					gatt.disconnect();
					gatt.close();
				}
			} else if (newState == 0 && status == 0) {
				// This is a glitch...if this happens then don't generate the alert (side of caution)
				Log.i("DLMNS_Service", "Setting possible_success to true");
				myDevice.notifyInRange();
				myDevice.possible_success = true;
			}
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,int status) { 
			KnownDevice myDevice = getKnownDevice(gatt.getDevice().hashCode());
			if (status == 0) {
				if (myDevice.tagCount != characteristic.getIntValue(17, 0)) {

					// 1. Cancel the previous alarm that was created (am.cancel(PI) doesn't reliably always work)
					if (myDevice.new_duty_cycle >= myDevice.getDutyCycle()) {
						myDevice.ignoreNext = true;
					}
					
					boolean dutyCycleChanged = false;
					if (myDevice.new_duty_cycle != myDevice.getDutyCycle()) {
						dutyCycleChanged = true;
					}

					myDevice.setDutyCycle(myDevice.new_duty_cycle); // Handshake complete...assume duty cycle write was succesful
					myDevice.tagCount = characteristic.getIntValue(17, 0);

					// 2. Set a new one (the tag should do the same)
					setConnectAlarm(myDevice.getDutyCycle() + 3, gatt.getDevice().hashCode());
					gatt.disconnect();
					gatt.close();
					myDevice.notifyInRange();
					myDevice.connection_success = true;
					
					if (dutyCycleChanged) {
						notifyDataChanged();
						updateStorageFile();
					}
					
				} 
			} else { // We didn't connect
				gatt.disconnect();
				gatt.close();
				connectDevice(myDevice);
			}

		}

		@Override
		public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

			KnownDevice myDevice = getKnownDevice(gatt.getDevice().hashCode());

			try {
				BluetoothGattService dlmns_service = gatt.getService(DLMNSGattAttributes.lookup("DLMNS Service"));
				BluetoothGattCharacteristic ack_char = dlmns_service.getCharacteristic(DLMNSGattAttributes.lookup("Acknowledge"));
				myDevice.possible_success = true; // This is just to avoid unnecessary alerts - still needs to be verified if this works.
				// Looks like sometimes the onCharacteristicRead callback isn't always called. We may need to add a handler here
				// and check that the callback already executes, or we won't be able to catch it.
				gatt.readCharacteristic(ack_char);
			} catch (Exception ex) { // We didn't connect...try again
				gatt.disconnect();
				gatt.close();
				connectDevice(myDevice);
			}

		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status){

			KnownDevice myDevice = getKnownDevice(gatt.getDevice().hashCode());

			// Get the BLE Service
			try {
				BluetoothGattService dlmns_service = gatt.getService(DLMNSGattAttributes.lookup("DLMNS Service"));
				BluetoothGattCharacteristic duty_cycle_char = dlmns_service.getCharacteristic(DLMNSGattAttributes.lookup("Duty Cycle"));
				// Decode the new duty cycle here
				duty_cycle_char.setValue(decodeDutyCycle(myDevice.new_duty_cycle));
				gatt.writeCharacteristic(duty_cycle_char);
			} catch (Exception ex) { // We really didn't connect
				gatt.disconnect();
				gatt.close();
				connectDevice(myDevice);
			}
		}
	};

	public class LocalBinder extends Binder {
		DLMMonitorService getService() {
			return DLMMonitorService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private KnownDevice getKnownDevice(int bleHash) {
		for (KnownDevice dev : monitoredDevices) {
			if (dev.getDeviceContext().hashCode() == bleHash) {
				return dev;
			}
		}
		return null;
	}
	
	private KnownDevice getKnownDevice(String macAddress) {
		for (KnownDevice dev : monitoredDevices) {
			if (dev.mac_address.equals(macAddress)) {
				return dev;
			}
		}
		return null;
	}

	private byte[] decodeDutyCycle(int dutyCycle) {
		switch (dutyCycle) {
		case 32:
			return new byte[] {(byte)0x01};
		case 64:
			return new byte[] {(byte)0x02};
		case 128:
			return new byte[] {(byte)0x03};
		case 256:
			return new byte[] {(byte)0x04};
		default:
			return null;
		}
	}

	private class connectionCheck implements Runnable {

		private KnownDevice foundDevice;

		public connectionCheck(KnownDevice foundDevice) {
			this.foundDevice = foundDevice;
		}

		public void run() {

			if (!foundDevice.connection_success && (foundDevice.possible_success || !foundDevice.connectionStateCallBackCalled)) {
				Log.i("DLMNS_Service", "Processing Handler - reconnecting");
				try {
					foundDevice.currentGattConnection.disconnect();
					foundDevice.currentGattConnection.close();
				} catch (Exception ex) {}

				if (foundDevice.connectAttempts == 2) {
					//resetBluetooth();
				}

				try {
					Thread.sleep(500);
				} catch (Exception ex) {}

				if (foundDevice.connectAttempts-- > 0) {
					Handler scan_handler = new Handler();
					connectionCheck r = new connectionCheck(foundDevice);
					foundDevice.connectionStateCallBackCalled = false;
					foundDevice.currentGattConnection = connectDevice(foundDevice);
					scan_handler.postDelayed(r, 3000);
				} else if (!foundDevice.possible_success){ // Air on the side of caution and don't alert
					foundDevice.notifyOutOfRange();
					foundDevice.generateNotification();
				}
			} 
		} 
	}
	
	public void removePendingAlarms() {
		for (PendingIntentInfo pii : pendingIntents) {
			am.cancel(pii.intent);
		}
	}
	
	private void removePendingIntent(int rc, int deviceHash) {
		for (int i = 0; i < pendingIntents.size(); i++) {
			PendingIntentInfo pii = pendingIntents.get(i);
			if ((pii.deviceHash == deviceHash) && (pii.requestCode == rc)) {
				pendingIntents.remove(i);
				return;
			}
		}
	}

	public BroadcastReceiver MyReceiver = new BroadcastReceiver()  {
		@Override
		public void onReceive(Context c, Intent i) {

			lastEntry = SystemClock.elapsedRealtime();

			// Try to get the hashcode of the BluetoothDevice
			int deviceHash = 0;
			int requestCode = 0;
			
			try {
				deviceHash = i.getIntExtra("hash_id", 0);
				requestCode = i.getIntExtra("requestCode", 0);
			} catch (Exception ex) {
				return;
			}
			
			removePendingIntent(requestCode, deviceHash);
			
			KnownDevice foundDevice = getKnownDevice(deviceHash);
			if (foundDevice == null) return;
			
			foundDevice.connection_success = false;

			if (!foundDevice.ignoreNext) {

				// Reset the alarm for the next time
				if (++(foundDevice.connectCount) >= 10) {
					foundDevice.connectCount = 0;

					if (foundDevice.new_duty_cycle >= foundDevice.getDutyCycle()) {
						setConnectAlarm(foundDevice.getDutyCycle() + 1, deviceHash);
					}

				} else {
					foundDevice.connectCount++;
					if (foundDevice.new_duty_cycle >= foundDevice.getDutyCycle()) {
						setConnectAlarm(foundDevice.getDutyCycle(), deviceHash);
					}
				}


				// Make the connection
				if (foundDevice != null) {

					Handler scan_handler = new Handler();
					connectionCheck r = new connectionCheck(foundDevice);

					foundDevice.connectAttempts = 3;
					foundDevice.possible_success = false;
					foundDevice.connectionStateCallBackCalled = false;
					foundDevice.currentGattConnection = connectDevice(foundDevice);
					scan_handler.postDelayed(r, 3000);
				}

			} else {
				foundDevice.ignoreNext = false;
			}
		}
	};

	private class resetThread extends Thread {

		public resetThread() {
			super();
			run();
		}

		@Override
		public void run() {
			bluetoothAdapter.disable();
			while (bluetoothAdapter.isEnabled()) {/*spin*/}
			bluetoothAdapter.enable();
			while (!bluetoothAdapter.isEnabled()) {/*spin*/}
		}
	};

	public void resetBluetooth() {

		while ((new resetThread()).isAlive()) {/*spin*/}
		try {
			Thread.sleep(500);
		} catch (Exception ex) {}
		return;

	}

	public BluetoothGatt connectDevice(KnownDevice dev) {
		return dev.currentGattConnection = dev.getDeviceContext().connectGatt(getApplicationContext(), false, new MyBleCallback());
	}

	public boolean initialize() {
		// Setup the Bluetooth Adapter
		final BluetoothManager bluetoothManager =
				(BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		bluetoothAdapter = bluetoothManager.getAdapter();

		registerReceiver(MyReceiver, new IntentFilter("com.gt.seniordesign.connectTimer"));
		
		pendingIntents = new ArrayList<PendingIntentInfo>();
		monitoredDevices = new ArrayList<KnownDevice>() {
			@Override
			public boolean add(KnownDevice dev) {
				boolean ret = super.add(dev);
				updateStorageFile();
				notifyDataChanged();
				return ret;
			}
			
			@Override 
			public KnownDevice remove(int pos) {
				KnownDevice rm = super.remove(pos);
				notifyDataChanged();
				updateStorageFile();
				return rm;
			}
		};

		loadFromStorageFile();

		if (!monitoredDevices.isEmpty()) {
			Handler scan_handler = new Handler();
			routineScanCheck r = new routineScanCheck();
			startScan(); // This re-acquires lost devices
			scan_handler.postDelayed(r, 400*1000);
			
			try {
				Thread.sleep(1000);
			} catch (Exception ex) {
				
			}
		}

		return true;
	}

	public void loadFromStorageFile() {

		try {
			Scanner sc = new Scanner(new File(getApplicationContext().getFilesDir(), "StoredTags"));

			while (sc.hasNext()) {
				String readString[] = sc.nextLine().split(",");
				String name = readString[0];
				String addr = readString[1];
				String norificationsEnabled = readString[3];
				int dutyCycle = Integer.parseInt(readString[2]);
				boolean notifyEnabled = Boolean.parseBoolean(norificationsEnabled);

				KnownDevice newDev = new KnownDevice(addr, name, dutyCycle, notifyEnabled);
				newDev.setDutyCycle(dutyCycle);
				monitoredDevices.add(newDev);
			}
			sc.close();

		} catch (Exception ex) {
			ex.printStackTrace();
		} finally {}
	};

	public void updateStorageFile() {
		try {
			File file = new File(getApplicationContext().getFilesDir(), "StoredTags");
			file.delete();
			file = new File(getApplicationContext().getFilesDir(), "StoredTags");

			FileWriter myWriter = new FileWriter(file);

			for (KnownDevice dev : monitoredDevices) {
				String myString = dev.getName() + ',' + dev.mac_address + ',' + dev.getDutyCycle() + ',' + dev.notificationsEnabled + '\n';
				myWriter.write(myString);
			}

			myWriter.flush();
			myWriter.close();

		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public void startScan() {

		try {
			Thread.sleep(1000);
		} catch (Exception ex) {}

		Thread scanThread = new Thread() {
			@Override
			public void run() {
				bluetoothAdapter.startLeScan(mLeScanCallback);
			}
		};
		scanThread.start();
	}

	private class routineScanCheck implements Runnable {
		public void run() {
			bluetoothAdapter.stopLeScan(mLeScanCallback);
		} 
	}

	private BluetoothAdapter.LeScanCallback mLeScanCallback =
			new BluetoothAdapter.LeScanCallback() {

		@Override
		public void onLeScan(final BluetoothDevice device, final int rssi, byte[] scanRecord) {
			KnownDevice currentDev = getKnownDevice(device.getAddress()); 
			if ((currentDev != null) && (!currentDev.acquired)) {
				Handler newHandler = new Handler(getBaseContext().getMainLooper());
				Runnable r = new Runnable() {
					@Override
					public void run() {
						KnownDevice currentDev = getKnownDevice(device.getAddress());
						currentDev.thisDevice = device;
						if (!currentDev.acquired) {
							setConnectAlarm(currentDev.getDutyCycle() + 3, device.hashCode());
							currentDev.currentGattConnection = device.connectGatt(getApplicationContext(), false, new MyBleCallback());
							notifyDataChanged();
							currentDev.acquired = true;
						}
					}
				};
				newHandler.post(r);
			}
		}
	};

	public void addNewDevice(KnownDevice myDevice) {

		// Add the device to our list
		monitoredDevices.add(myDevice);

		// This is the hashcode for the actual BluetoothDevice instance
		int deviceHash = myDevice.getDeviceContext().hashCode();

		// See if we need to add space between the connections (needs to be verified)
		while (SystemClock.elapsedRealtime() < lastEntry + 1000*15) { /*spin*/ }
		lastEntry = SystemClock.elapsedRealtime();
		setConnectAlarm(myDevice.getDutyCycle() + 3, deviceHash);

		Handler scan_handler = new Handler();
		connectionCheck r = new connectionCheck(myDevice);
		myDevice.connectAttempts = 2;
		myDevice.possible_success = false;
		myDevice.connectionStateCallBackCalled = false;

		connectDevice(myDevice); // This creates a new thread and avoids locking up UI (Android Warning)
		scan_handler.postDelayed(r, 5000);
		myDevice.connectCount++;
	}

	public void notifyDataChanged() {

		Handler newHandler = new Handler(getBaseContext().getMainLooper());
		Runnable r = new Runnable() {
			@Override
			public void run() {
				MainActivity.monitoredDevicesAdapter.notifyDataSetChanged();
			}
		};
		newHandler.post(r);

	}

	public ArrayList<KnownDevice> getKnownDevices() {
		return monitoredDevices;
	}

	public void setConnectAlarm(int time_secs, int deviceHash) {
		Intent newIntent = new Intent("com.gt.seniordesign.connectTimer");
		newIntent.putExtra("hash_id", deviceHash);
		int requestCode = (int)System.currentTimeMillis();
		newIntent.putExtra("requestCode", requestCode);
		PendingIntent pi = PendingIntent.getBroadcast(this, requestCode, newIntent,0);
		pendingIntents.add(new PendingIntentInfo(requestCode, deviceHash, pi));
		am = (AlarmManager)(this.getSystemService(Context.ALARM_SERVICE ));
		am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime() + 1000*time_secs, pi);
	}

}