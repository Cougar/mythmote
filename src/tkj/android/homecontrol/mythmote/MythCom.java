/*
 * Copyright (C) 2010 Thomas G. Kenny Jr
 *
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 *
 *      http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package tkj.android.homecontrol.mythmote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

/** Class that handles network communication with mythtvfrontend **/
public class MythCom {

	public interface StatusChangedEventListener extends EventListener {
		public void statusChanged(String statusMsg, int statusCode);
		public void mythTvLocationChanged(String location);
	}

	public static final int DEFAULT_MYTH_PORT = 6546;
	public static final int STATUS_DISCONNECTED = 0;
	public static final int STATUS_CONNECTED = 1;
	public static final int STATUS_CONNECTING = 3;
	public static final int STATUS_ERROR = 99;

	private static final int SOCKET_TIMEOUT = 2000;
	
	private static Timer _timer;
	private static Toast _toast;
	private static Socket _socket;
	private static BufferedWriter _outputStream;
	private static BufferedReader  _inputStream;
	private static Activity _parent;
	private static ConnectivityManager _conMgr;
	private static String _status;
	private static int _statusCode;
	private static StatusChangedEventListener _statusListener;
	private static FrontendLocation _frontend;
	private static String _lastLocation;

	private final Handler mHandler = new Handler();
	private final Runnable mSocketActionComplete = new Runnable()
	{
		public void run()
		{
			setStatus(_status, _statusCode);
			if(_statusCode!=STATUS_CONNECTING)
			    _toast.cancel();
		}

	};
	
	/** TimerTask that probes the current connection for its mythtv screen.  **/
	private static TimerTask timerTaskCheckStatus;

	
	/** Parent activity is used to get context */
	public MythCom(Activity parentActivity)
	{
		_parent = parentActivity;
		_statusCode = STATUS_DISCONNECTED;
	}
	
	/** Connects to the given address and port. Any existing connection will be broken first **/
	public void Connect(FrontendLocation frontend)
	{
		//read status update interval preference
		int updateInterval = _parent.getSharedPreferences(MythMotePreferences.MYTHMOTE_SHARED_PREFERENCES_ID, Context.MODE_PRIVATE)
		.getInt(MythMotePreferences.PREF_STATUS_UPDATE_INTERVAL, 5000);
		
		//schedule update timer
		scheduleUpdateTimer(updateInterval);

		//get connection manager
		_conMgr = (ConnectivityManager) _parent.getSystemService(Context.CONNECTIVITY_SERVICE);

		// set address and port
		_frontend = frontend;

		//create toast for all to eat and enjoy
		_toast = Toast.makeText(_parent.getApplicationContext(), R.string.attempting_to_connect_str, Toast.LENGTH_SHORT);
		_toast.setGravity(Gravity.CENTER, 0, 0);
		_toast.show();

		this.setStatus("Connecting", STATUS_CONNECTING);

		// create a socket connecting to the address on the requested port
		this.connectSocket();
	}
	
	/** Closes the socket if it exists and it is already connected **/
	public void Disconnect()
	{
        _statusCode=STATUS_DISCONNECTED;
		try
		{
			//send exit if connected
			if(this.IsConnected())
				this.sendData("exit\n");

			// check if output stream exists
			if (_outputStream != null) {
				_outputStream.close();
				_outputStream = null;
			}

			// check if input stream exists
			if (_inputStream != null) {
				// close input stream
				_inputStream.close();
				_inputStream = null;
			}
			if(_socket != null)
			{
			    if(!_socket.isClosed())
				    _socket.close();
			
				_socket = null;
			}
			if(_conMgr != null)
				_conMgr = null;
		}
		catch(IOException ex)
		{
			this.setStatus("Disconnect I/O error", STATUS_ERROR);
		}
	}

	public synchronized List<String> SendCommand(String command, boolean okExpected) {
		if (this.IsConnected()) {
			this.sendData(command);
			List<String> results = this.readResult();
			if (okExpected) {
				if (results.size() == 0) {
					Log.e(MythMote.LOG_TAG, "Command " + command + " returned no results");
					return null;
				}
				if (!results.get(0).equals("OK")) {
					Log.e(MythMote.LOG_TAG, "Command " + command + " returned " + results.get(0));
					return null;
				}
			}
			return results;
		} else {
			Log.e(MythMote.LOG_TAG, "Unable to send command: Not connected");
			return null;
		}
	}

	public void SendJumpCommand(String jumpPoint) {
		this.SendCommand(String.format("jump %s\n", jumpPoint), true);
		checkMythTvLocation();
	}

	public void SendKey(String key) {
		this.SendCommand(String.format("key %s\n", key), true);
	}

	public void SendKey(char key) {
		this.SendCommand(String.format("key %s\n", key), true);
	}

	public void SendPlayCommand(String command) {
		this.SendCommand(String.format("play %s\n", command), true);
		checkMythTvLocation();
	}

	public List<String> SendQuery(String query)
	{
		return SendCommand(String.format("query %s\n", query), false);
	}

	public void SetOnStatusChangeHandler(StatusChangedEventListener listener) {
		_statusListener = listener;
	}

	public String GetStatusStr() {
		return _status;
	}

	public boolean IsNetworkReady() {
		if (_conMgr != null && _conMgr.getActiveNetworkInfo().isConnected())
			return true;
		return false;
	}
	
	public boolean IsConnected()
	{
		if(_statusCode==STATUS_CONNECTED) return true;
		return false;
	}

	public boolean IsConnecting()
	{
		if(_statusCode==STATUS_CONNECTING) return true;
		return false;
	}
	
	/** Connects _socket to _frontend using a separate thread  **/
	private void connectSocket()
	{
		if(_socket==null)
		    _socket = new Socket();
		
		Thread thread = new Thread()
		{
			public void run()
			{
				try
				{
					_socket.connect(new InetSocketAddress(_frontend.Address, _frontend.Port));
					_socket.setSoTimeout(SOCKET_TIMEOUT);
					
					if(_socket.isConnected())
					{
					    _outputStream = new BufferedWriter(new OutputStreamWriter(_socket.getOutputStream()));
					    _inputStream = new BufferedReader(new InputStreamReader(_socket.getInputStream()));
					}
					else
					{
						_status = "Could not open socket.";
						_statusCode = STATUS_ERROR;
					}

					//check if everything was connected OK
					if(!_socket.isConnected() || _outputStream == null)
					{
						_status = "Unknown error getting output stream.";
						_statusCode = STATUS_ERROR;
					}
					else
					{
						_status = _frontend.Name + " - Connected";
						_statusCode = STATUS_CONNECTED;
					}

				}
				catch (UnknownHostException e)
				{
					_status = "Unknown host: " + _frontend.Address;
					_statusCode = STATUS_ERROR;
				}
				catch (IOException e)
				{
					_status = "IO Except: " + e.getLocalizedMessage() + ": " + _frontend.Address;
					_statusCode = STATUS_ERROR;
					if(_inputStream!=null)
					{
						_inputStream=null;
					}
					if(_socket!=null)
					{
						if(!_socket.isClosed())
						{
							try { _socket.close(); } 
							catch (IOException e1) { }
							_socket = null;
						}
					}
				}

				// post results
				mHandler.post(mSocketActionComplete);
			}
		};

		// run thread
		thread.start();
	}
	
	/**
	 * Sends data to the output stream of the socket.
	 * Attempts to reconnect socket if connection does not already exist.
	 */
	private void sendData(String data)
	{
		if (_outputStream == null) {
			Log.e(MythMote.LOG_TAG, "Unable to send data: No outputStream available");
		}
		try {
			// If there is anything in the inputStream, clear it before sending the command
			if (_inputStream.ready()) {
				readResult();
			}

			if(!data.endsWith("\n"))
				data = String.format("%s\n", data);

			_outputStream.write(data);
			_outputStream.flush();
		} catch (IOException ex) {
			Log.e(MythMote.LOG_TAG, "Unable to send data", ex);
			this.setStatus(ex.getLocalizedMessage() + ": " + _frontend.Address , STATUS_ERROR);
			this.Disconnect();
		}
	}
	
	/**
	 * Reads all data returned from the server until the prompt.
	 */
	private List<String> readResult()
	{
		List<String> result = new ArrayList<String>();
		if (this.IsConnected() && _inputStream != null) {
			String line = readLine();
			while (line != null) {
				result.add(line);
				line = readLine();
			}
		}

		return result;
	}

	/**
	 * Read a single line from the server or return null if the prompt is received.
	 */
	private String readLine() {
		String outString = "";
		char[] twoChars = new char[2];
		try {
			_inputStream.read(twoChars);
			if (twoChars[0] == '#' && twoChars[1] == ' ') {
				return null;
			} else {
				outString += String.valueOf(twoChars[0]) + String.valueOf(twoChars[1]) + _inputStream.readLine();
			}
		} catch (SocketTimeoutException stex) {
			Log.w(MythMote.LOG_TAG, "Socket timeout while waiting for prompt");
			return null;
		} catch (IOException e) {
			Log.e(MythMote.LOG_TAG, "IO Error reading data", e);
			this.setStatus(e.getLocalizedMessage() + ": " + _frontend.Address, STATUS_ERROR);
			this.Disconnect();
			return null;
		}
		return outString;
	}
	
	/** Sets _status and fires the StatusChanged event **/
	private void setStatus(final String statusMsg, final int code)
	{
		_parent.runOnUiThread(new Runnable(){

			public void run() {
				_status = statusMsg;
				if (_statusListener != null)
					_statusListener.statusChanged(statusMsg, code);
			}

		});
	}
	
	/** Sets _status and fires the StatusChanged event **/
	private void setMythTvLocation(final String locationMsg)
	{
		_parent.runOnUiThread(new Runnable(){

			public void run() {
				if (_statusListener != null)
					_statusListener.mythTvLocationChanged(locationMsg);
			}

		});
	}

	/** Creates the update timer and schedules it for the given interval.
	 * If the timer already exists it is destroyed and recreated. */
	private void scheduleUpdateTimer(int updateInterval)
	{
		try
		{
			//close down the existing timer.
			if(_timer != null)
			{
				_timer.cancel();
				_timer.purge();
				_timer = null;
			}
			
			//clear timer task
			if(timerTaskCheckStatus != null)
			{
				timerTaskCheckStatus.cancel();
				timerTaskCheckStatus = null;
			}

			//(re)schedule the update timer
			if(updateInterval > 0)
			{
				//create timer task
				timerTaskCheckStatus = new TimerTask()
				{
					//Run at every timer tick
					public void run() 
					{
						checkMythTvLocation();
					}
				};
					
				_timer = new Timer();
				_timer.schedule(timerTaskCheckStatus, updateInterval, updateInterval);
			}
		}
		catch(Exception ex)
		{
			Log.e(MythMote.LOG_TAG, "Error scheduling status update timer.", ex);
		}
	}

	private void checkMythTvLocation() {
		//only if socket is connected
		if(IsConnected() && !IsConnecting())
		{
			List<String> queryResult = SendQuery("location");
			//set disconnected status if nothing is returned.
			if(queryResult == null)
			{
				setStatus("Disconnected", STATUS_DISCONNECTED);
			}
			else if (queryResult.size() > 0)
			{
				setStatus(_frontend.Name + " - Connected", STATUS_CONNECTED);
				String location = queryResult.get(0);
				if (!location.equals(_lastLocation)) {
					setMythTvLocation(location);
				}
				_lastLocation = location;
			}
		}
	}
	
}
