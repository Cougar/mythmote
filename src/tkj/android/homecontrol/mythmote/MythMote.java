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

import tkj.android.homecontrol.mythmote.db.MythMoteDbManager;
import tkj.android.homecontrol.mythmote.keymanager.KeyBindingEntry;
import tkj.android.homecontrol.mythmote.keymanager.KeyBindingManager;
import tkj.android.homecontrol.mythmote.keymanager.KeyMapBinder;
import android.app.AlertDialog;
import android.app.TabActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TextView;

public class MythMote extends TabActivity implements TabHost.TabContentFactory,
		OnTabChangeListener, LocationChangedEventListener,
		MythCom.StatusChangedEventListener, KeyMapBinder {

	public static final int SETTINGS_ID = Menu.FIRST;
	public static final int RECONNECT_ID = Menu.FIRST + 1;
	public static final int SELECTLOCATION_ID = Menu.FIRST + 2;

	public static final String NAME_MENU_TAB = "TabMenu";
	public static final int INDEX_MENU_TAB = 0;
	public static final String NAME_VIDEO_TAB = "TabVideo";
	public static final int INDEX_VIDEO_TAB = 1;
	public static final String NAME_LIVETV_TAB = "TabLiveTV";
	public static final int INDEX_LIVETV_TAB = 2;

	public static final String LOG_TAG = "MythMote";

	private static final String KEY_VOLUME_DOWN = "[";
	private static final String KEY_VOLUME_UP = "]";

	private KeyBindingManager mKeyManager;

	private static TabHost sTabHost;
	private static MythCom sComm;
	private static FrontendLocation sLocation = new FrontendLocation();
	private static int sSelected = -1;

	/**
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.setContentView(R.layout.main);

		if (sComm == null) {
			// create comm class
			sComm = new MythCom(this);
		}
		// set status changed event handler
		sComm.SetOnStatusChangeHandler(this);

		// create tab UI
		sTabHost = getTabHost();

		// create tabs
		this.createTabs();

		// setup on tab change event
		sTabHost.setOnTabChangedListener(this);

		// set navigation tab and setup events
		sTabHost.setCurrentTab(0);

		// create key manager and load keys from DB
		mKeyManager = new KeyBindingManager(this, this, sComm);
		mKeyManager.loadKeys();
	}

	/**
	 * Called when the activity is resumed
	 */
	@Override
	public void onResume() {
		super.onResume();

		//Here we disconnect if connected because the selected location
		//may have changed from the preference activity. setSelectedLocation() will also trigger 
		//loading any other changed preferences
		
		// disconnect if connected
		if (sComm != null && (sComm.IsConnected() || sComm.IsConnecting())) {
			// force disconnected state
			sComm.Disconnect();
		}

		// set selected location and connect
		this.setSelectedLocation();
	}

	/**
	 * Called when the activity is paused
	 */
	@Override
	public void onPause() {
		super.onPause();
	}

	/**
	 * Called when the activity is being destroyed
	 */
	public void onDestroy() {
		super.onDestroy();

		if (sComm != null && sComm.IsConnected())
			sComm.Disconnect();
		sTabHost = null;

	}

	/**
	 * Called when device configuration changes occur. Configuration changes
	 * that cause this function to be called must be registered in
	 * AndroidManifest.xml
	 */
	public void onConfigurationChanged(Configuration config) {
		super.onConfigurationChanged(config);

		// make sure tabhost has been set
		if (sTabHost == null)
			sTabHost = this.getTabHost();

		// get current tab index
		int cTab = sTabHost.getCurrentTab();

		// set current tab to 0. Clear seems to fail when set to anything else
		sTabHost.setCurrentTab(0);

		// clear all tabs
		sTabHost.clearAllTabs();

		// create tabs
		this.createTabs();

		// set current tab back
		sTabHost.setCurrentTab(cTab);
	}

	/**
	 * Overridden to allow the hardware volume controls to influence the Myth
	 * front end volume control
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_VOLUME_DOWN:
			sComm.SendKey(KEY_VOLUME_DOWN);
			return true;
		case KeyEvent.KEYCODE_VOLUME_UP:
			sComm.SendKey(KEY_VOLUME_UP);
			return true;
		default:
			return super.onKeyDown(keyCode, event);

		}

	}

	/**
	 * Called to create the options menu once.
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		boolean result = super.onCreateOptionsMenu(menu);

		// create settings menu item
		menu.add(0, SETTINGS_ID, 0, R.string.settings_menu_str).setIcon(
				R.drawable.settings);

		// create reconnect menu item
		menu.add(0, RECONNECT_ID, 0, R.string.reconnect_str).setIcon(
				R.drawable.menu_refresh);

		// create select location menu item
		menu.add(0, SELECTLOCATION_ID, 0, R.string.selected_location_str)
				.setIcon(R.drawable.selected_location);

		// return results
		return result;
	}

	/**
	 * Called when a menu item is selected
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		try {
			// Check which menu item was selected
			switch (item.getItemId()) {
			case SETTINGS_ID:
				// Create mythmote preferences intent and start the activity
				Intent intent = new Intent(
						this,
						tkj.android.homecontrol.mythmote.MythMotePreferences.class);
				this.startActivity(intent);
				break;

			case RECONNECT_ID:
				if (sComm.IsConnected())
					sComm.Disconnect();

				if (this.setSelectedLocation())
					sComm.Connect(sLocation);
				break;

			case SELECTLOCATION_ID:
				// Displays the list of configured frontend locations.
				// Fires the locationChanged event when the user selects a
				// location
				// even if the user selects the same location already selected.
				MythMotePreferences.SelectLocation(this, this);
				break;
			}
			;
		} catch (android.content.ActivityNotFoundException ex) {
			// Show error when activity is not found
			AlertDialog.Builder diag = new AlertDialog.Builder(this);
			diag.setMessage(ex.getMessage());
			diag.setTitle("Error");
			diag.setNeutralButton("OK", null);
			diag.show();
		}
		return false;
	}

	/**
	 * Called when the selected tab page is changed
	 */
	public void onTabChanged(String arg0) {
		// load keybindings
		mKeyManager.loadKeys();

		// TODO
//		// setup the number tab's send keyboard input button
//		if (sTabHost.getCurrentTabTag().equals(NAME_NUMPAD_TAB)) {
//			setupSendKeyboardInputButton();
//		}
	}

	/**
	 * Called when a tab is selected. Returns the layout for the selected tab.
	 * Default is navigation tab
	 */
	public View createTabContent(String tag) {

		// check which tab content to return
		if (tag.equals(NAME_MENU_TAB)) {
			return this.getLayoutInflater().inflate(R.layout.menutab,
					this.getTabHost().getTabContentView(), false);
		} else if (tag.equals(NAME_VIDEO_TAB)) {
			return this.getLayoutInflater().inflate(R.layout.videotab,
					this.getTabHost().getTabContentView(), false);
		} else if (tag.equals(NAME_LIVETV_TAB)) {
			return this.getLayoutInflater().inflate(R.layout.livetvtab,
					this.getTabHost().getTabContentView(), false);
		} else {
			// default to menu tab view
			return this.getLayoutInflater().inflate(R.layout.menutab,
					this.getTabHost().getTabContentView(), false);
		}
	}

	/**
	 * Called when the frontend location is changed
	 */
	public void frontendLocationChanged() {
		if (sComm.IsConnected())
			sComm.Disconnect();

		if (this.setSelectedLocation())
			sComm.Connect(sLocation);
	}

	/**
	 * Called when MythCom status changes
	 */
	@Override
	public void statusChanged(String statusMsg, int statusCode) {
		// set title
		setTitle(statusMsg);

		// change color based on status code
		if (statusCode == MythCom.STATUS_ERROR) {
			setTitleColor(Color.RED);
		} else if (statusCode == MythCom.STATUS_DISCONNECTED) {
			setTitleColor(Color.RED);
		} else if (statusCode == MythCom.STATUS_CONNECTED) {
			setTitleColor(Color.GREEN);
		} else if (statusCode == MythCom.STATUS_CONNECTING) {
			setTitleColor(Color.YELLOW);
		}
	}

	/**
	 * Called when user's location in MythTV changes
	 */
	@Override
	public void mythTvLocationChanged(String location) {
		TextView mediaTitle = (TextView) findViewById(R.id.mediaTitle);
		TextView mediaSubtitle = (TextView) findViewById(R.id.mediaSubtitle);
		Button deleteButton = (Button) findViewById(R.id.ButtonDelete);
		deleteButton.setVisibility(View.INVISIBLE);
		if (location.startsWith("Playback Video")) {
			String[] components = location.split(" ");
			String time = components[2];
			mediaTitle.setText("Playing Video");
			mediaSubtitle.setText(time);
			sTabHost.setCurrentTab(INDEX_VIDEO_TAB);
		} else if (location.startsWith("Playback Recorded")) {
			String[] components = location.split(" ");
			String time = components[2];
			String timeRemaining = components[4];
			mediaTitle.setText("Playing Recording");
			mediaSubtitle.setText(time + " of " + timeRemaining);
			sTabHost.setCurrentTab(INDEX_VIDEO_TAB);
		} else if (location.startsWith("Playback LiveTV")) {
			String[] components = location.split(" ");
			String time = components[2];
			String timeRemaining = components[4];
			mediaTitle.setText("Playing Live TV");
			mediaSubtitle.setText(time + " of " + timeRemaining);
			sTabHost.setCurrentTab(INDEX_LIVETV_TAB);
		} else if (location.startsWith("mythvideo") || location.startsWith("playbackbox")) {
			mediaTitle.setText(location);
			mediaSubtitle.setText("");
			sTabHost.setCurrentTab(INDEX_MENU_TAB);
			deleteButton.setVisibility(View.VISIBLE);
		} else {
			mediaTitle.setText(location);
			mediaSubtitle.setText("");
			sTabHost.setCurrentTab(INDEX_MENU_TAB);
		}
	}

	/**
	 * Enable the long click and normal click actions where a long click will
	 * configure the button, and a normal tap will perform the command
	 * 
	 * This is the callback from the {@link KeyBindingManager}
	 */
	public View bind(KeyBindingEntry entry) {
		View v = this.findViewById(entry.getMythKey().getButtonId());
		if (null == v)
			return null;
		v.setOnLongClickListener(mKeyManager);
		v.setOnClickListener(mKeyManager);
		return v;
	}

	/**
	 * Reads the selected frontend from preferences and attempts to connect with
	 * MythCom.Connect()
	 */
	private boolean setSelectedLocation() {

		// load shared preferences
		this.loadSharedPreferences();

		// _location should be initialized
		if (sLocation == null) {
			Log.e(LOG_TAG,
					"Cannot set location. Location object not initialized.");
		}

		// create location database adapter
		MythMoteDbManager dbManager = new MythMoteDbManager(this);
		sLocation = dbManager.fetchFrontendLocation(sSelected);

		if (sLocation == null) {
			return false;
		}

		// connect to location
		sComm.Connect(sLocation);

		return true;
	}

	/**
	 * Creates and defines the OnClickListener for media tab's send keyboard
	 * input button.
	 */
	private void setupSendKeyboardInputButton() {
		// send keyboard input
		final Button buttonJump = (Button) this.findViewById(R.id.ButtonSend);
		final EditText textBox = (EditText) this
				.findViewById(R.id.EditTextKeyboardInput);
		if (buttonJump != null && textBox != null) {
			buttonJump.setOnClickListener(new OnClickListener() {
				public void onClick(View v) {

					// get send keyboard text
					Editable text = textBox.getText();
					int count = text.length();

					// for each character
					for (int i = 0; i < count; i++) {
						// get char
						char c = text.charAt(i);

						// check if it's whitespace
						if (Character.isWhitespace(c)) {
							if (c == '\t')// tab
							{
								sComm.SendKey("tab");
							} else if (c == ' ')// space
							{
								sComm.SendKey("space");
							} else if (c == '\r')// enter/return
							{
								sComm.SendKey("enter");
							}
						} else// not white space. Just send as is
						{
							sComm.SendKey(c);
						}
					}
				}
			});
		}
	}

	/**
	 * Called to create and add tabs to the tabhost
	 */
	private void createTabs() {
		// create tabs. Media tab is only used when large layouts are inactive
		sTabHost.addTab(sTabHost.newTabSpec(NAME_MENU_TAB)
				.setIndicator(NAME_MENU_TAB)
				.setContent(this));
		sTabHost.addTab(sTabHost.newTabSpec(NAME_VIDEO_TAB)
				.setIndicator(NAME_VIDEO_TAB)
				.setContent(this));
		sTabHost.addTab(sTabHost.newTabSpec(NAME_LIVETV_TAB)
				.setIndicator(NAME_LIVETV_TAB)
				.setContent(this));

		// resize tabs to remove useless space
		final int count = sTabHost.getTabWidget().getChildCount();
		for (int i = 0; i < count; i++)
			sTabHost.getTabWidget().getChildAt(i).getLayoutParams().height = 50;
	}

	/**
	 * Reads the mythmote shared preferences and sets local members accordingly.
	 */
	private void loadSharedPreferences() {
		// get shared preferences reference
		SharedPreferences pref = this.getSharedPreferences(
				MythMotePreferences.MYTHMOTE_SHARED_PREFERENCES_ID,
				MODE_PRIVATE);

		// get selected frontend id
		sSelected = pref.getInt(MythMotePreferences.PREF_SELECTED_LOCATION, -1);

		// get keybindings editable preference
		this.mKeyManager.setEditingEnabled(pref.getBoolean(
				MythMotePreferences.PREF_KEYBINDINGS_EDITABLE, true));

		// set the hapticfeedback setting in keymanager
		this.mKeyManager.setHapticFeedbackEnabled(pref.getBoolean(
				MythMotePreferences.PREF_HAPTIC_FEEDBACK_ENABLED, false));

		// done with pref ref
		pref = null;
	}
}
