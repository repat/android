package com.twofours.surespot.chat;

import io.socket.IOAcknowledge;
import io.socket.IOCallback;
import io.socket.SocketIO;
import io.socket.SocketIOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewPager;
import ch.boye.httpclientandroidlib.cookie.Cookie;

import com.actionbarsherlock.view.MenuItem;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.twofours.surespot.IdentityController;
import com.twofours.surespot.StateController.FriendState;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.common.SurespotConfiguration;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.encryption.EncryptionController;
import com.twofours.surespot.friends.Friend;
import com.twofours.surespot.friends.FriendAdapter;
import com.twofours.surespot.network.IAsyncCallback;
import com.viewpagerindicator.TitlePageIndicator;

public class ChatController {

	private static final String TAG = "ChatController";
	private static final int STATE_CONNECTING = 0;
	private static final int STATE_CONNECTED = 1;
	private static final int STATE_DISCONNECTED = 2;

	private static final int MAX_RETRIES = 5;
	private SocketIO socket;
	private int mRetries = 0;
	private Timer mBackgroundTimer;
	// private TimerTask mResendTask;

	private IOCallback mSocketCallback;

	private ConcurrentLinkedQueue<SurespotMessage> mSendBuffer = new ConcurrentLinkedQueue<SurespotMessage>();
	private ConcurrentLinkedQueue<SurespotMessage> mResendBuffer = new ConcurrentLinkedQueue<SurespotMessage>();
	private ConcurrentLinkedQueue<SurespotControlMessage> mControlResendBuffer = new ConcurrentLinkedQueue<SurespotControlMessage>();

	private int mConnectionState;
	private boolean mOnWifi;
	private NotificationManager mNotificationManager;
	private BroadcastReceiver mConnectivityReceiver;
	private HashMap<String, ChatAdapter> mChatAdapters;
	private HashMap<String, Integer> mEarliestMessage;

	private FriendAdapter mFriendAdapter;
	private ChatPagerAdapter mChatPagerAdapter;
	private ViewPager mViewPager;
	private TitlePageIndicator mIndicator;
	private FragmentManager mFragmentManager;
	private int mLatestUserControlId;
	private ArrayList<MenuItem> mMenuItems;
	private HashMap<String, LatestIdPair> mPreConnectIds;

	private static String mCurrentChat;
	private static boolean mPaused = true;

	private Context mContext;
	public static final int MODE_NORMAL = 0;
	public static final int MODE_SELECT = 1;

	private int mMode = MODE_NORMAL;

	private IAsyncCallback<Void> mCallback401;

	public ChatController(Context context, FragmentManager fm, IAsyncCallback<Void> callback401) {
		SurespotLog.v(TAG, "constructor: " + this);
		mContext = context;
		mCallback401 = callback401;
		mEarliestMessage = new HashMap<String, Integer>();
		mChatAdapters = new HashMap<String, ChatAdapter>();
		mFriendAdapter = new FriendAdapter(mContext);
		mPreConnectIds = new HashMap<String, ChatController.LatestIdPair>();
		loadState();

		mFragmentManager = fm;
		mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

		setOnWifi();

		// mViewPager.setOffscreenPageLimit(2);

		mSocketCallback = new IOCallback() {

			@Override
			public void onMessage(JSONObject json, IOAcknowledge ack) {
				try {
					SurespotLog.v(TAG, "JSON Server said:" + json.toString(2));

				}
				catch (JSONException e) {
					SurespotLog.w(TAG, "onMessage", e);
				}
			}

			@Override
			public void onMessage(String data, IOAcknowledge ack) {
				SurespotLog.v(TAG, "Server said: " + data);
			}

			@Override
			public synchronized void onError(SocketIOException socketIOException) {
				// socket.io returns 403 for can't login
				if (socketIOException.getHttpStatus() == 403) {
					socket = null;
					logout();
					mCallback401.handleResponse(null);
					return;
				}

				SurespotLog.w(TAG, "an Error occured, attempting reconnect with exponential backoff, retries: " + mRetries,
						socketIOException);

				// if (mResendTask != null) {
				// mResendTask.cancel();
				// }

				setOnWifi();
				// kick off another task
				if (mRetries < MAX_RETRIES) {

					if (mReconnectTask != null) {
						mReconnectTask.cancel();
					}

					int timerInterval = (int) (Math.pow(2, mRetries++) * 1000);
					SurespotLog.v(TAG, "Starting another task in: " + timerInterval);

					mReconnectTask = new ReconnectTask();
					if (mBackgroundTimer == null) {
						mBackgroundTimer = new Timer("backgroundTimer");
					}
					mBackgroundTimer.schedule(mReconnectTask, timerInterval);
				}
				else {
					// TODO tell user
					SurespotLog.w(TAG, "Socket.io reconnect retries exhausted, giving up.");
					// TODO more persistent error

					Utils.makeLongToast(mContext, "could not connect to the server");

					mCallback401.handleResponse(null);
					// Utils.makeToast(this,mContext,
					// "Can not connect to chat server. Please check your network and try again.",
					// Toast.LENGTH_LONG).show(); // TODO tie in with network controller 401 handling
					// Intent intent = new Intent(mContext, MainActivity.class);
					// intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
					// mContext.startActivity(intent);

				}
			}

			@Override
			public void onDisconnect() {
				SurespotLog.v(TAG, "Connection terminated.");
				// socket = null;
			}

			@Override
			public void onConnect() {
				SurespotLog.v(TAG, "socket.io connection established");
				setState(STATE_CONNECTED);
				setOnWifi();
				mRetries = 0;

				if (mBackgroundTimer != null) {
					mBackgroundTimer.cancel();
					mBackgroundTimer = null;
				}

				if (mReconnectTask != null && mReconnectTask.cancel()) {
					SurespotLog.v(TAG, "Cancelled reconnect timer.");
					mReconnectTask = null;
				}

				connected();

			}

			@Override
			public void on(String event, IOAcknowledge ack, Object... args) {

				SurespotLog.v(TAG, "Server triggered event '" + event + "'");
				if (event.equals("control")) {
					try {
						SurespotControlMessage message = SurespotControlMessage.toSurespotControlMessage(new JSONObject((String) args[0]));

						if (message.getType().equals("user")) {
							mLatestUserControlId = message.getId();
							if (message.getAction().equals("revoke")) {
								IdentityController.updateLatestVersion(mContext,
										ChatUtils.getOtherUser(message.getData(), IdentityController.getLoggedInUser()),
										message.getMoreData());
							}
							else if (message.getAction().equals("added")) {
								mFriendAdapter.addNewFriend(message.getData());
							}
							else if (message.getAction().equals("invited")) {
								mFriendAdapter.addFriendInvited(message.getData());
							}
							else if (message.getAction().equals("invite")) {
								mFriendAdapter.addFriendInviter(message.getData());
							}
							else if (message.getAction().equals("decline")) {
								mFriendAdapter.removeFriend(message.getData());
							}

						}

						else if (message.getType().equals("message")) {
							String otherUser = ChatUtils.getOtherSpotUser(message.getData(), IdentityController.getLoggedInUser());
							Friend friend = mFriendAdapter.getFriend(otherUser);
							ChatAdapter chatAdapter = mChatAdapters.get(otherUser);

							if (chatAdapter != null) {
								SurespotMessage dMessage = chatAdapter.getMessageById(Integer.parseInt(message.getMoreData()));
								if (dMessage != null) {
									if (message.getAction().equals("delete")) {

										// if it's an image blow the http cache entry away
										if (dMessage.getMimeType() != null) {
											if (dMessage.getMimeType().equals(SurespotConstants.MimeTypes.IMAGE)) {
												MainActivity.getNetworkController().purgeCacheUrl(dMessage.getData());
											}

											boolean controlFromMe = message.getFrom().equals(IdentityController.getLoggedInUser());
											boolean myMessage = dMessage.getFrom().equals(IdentityController.getLoggedInUser());

											// if i sent the delete, or it's not my message then delete it
											// (if someone else deleted my message we don't care)
											if (controlFromMe || !myMessage) {
												SurespotLog.v(TAG, "deleting message");
												chatAdapter.deleteMessageById(dMessage.getId());
											}
										}
									}
									else {
										if (message.getAction().equals("shareable") || message.getAction().equals("notshareable")) {
											SurespotLog.v(TAG, "setting message " + message.getAction());
											dMessage.setShareable(message.getAction().equals("shareable") ? true : false);
										}

									}
								}
								chatAdapter.notifyDataSetChanged();
							}
							friend.setLastReceivedMessageControlId(message.getId());
							friend.setAvailableMessageControlId(message.getId());
						}
					}
					catch (JSONException e) {
						SurespotLog.w(TAG, "error creating control message: " + e.toString(), e);
					}
				}
				if (event.equals("message")) {

					// TODO check who from
					try {
						JSONObject jsonMessage = new JSONObject((String) args[0]);
						SurespotLog.v(TAG, "received message: " + jsonMessage.toString());
						SurespotMessage message = SurespotMessage.toSurespotMessage(jsonMessage);
						updateUserMessageIds(message);
						checkAndSendNextMessage(message);

					}
					catch (JSONException e) {
						SurespotLog.w(TAG, "on", e);
					}

				}
			}
		};

		mConnectivityReceiver = new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				SurespotLog.v(TAG, "Connectivity Action");
				ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
				if (networkInfo != null) {
					SurespotLog.v(TAG, "isconnected: " + networkInfo.isConnected());
					SurespotLog.v(TAG, "failover: " + networkInfo.isFailover());
					SurespotLog.v(TAG, "reason: " + networkInfo.getReason());
					SurespotLog.v(TAG, "type: " + networkInfo.getTypeName());

					// if it's not a failover and wifi is now active then initiate reconnect
					if (!networkInfo.isFailover() && (networkInfo.getType() == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected())) {
						synchronized (ChatController.this) {
							// if we're not connecting, connect
							if (getState() != STATE_CONNECTING && !mOnWifi) {

								SurespotLog.v(TAG, "Network switch, Reconnecting...");

								setState(STATE_CONNECTING);

								mOnWifi = true;
								disconnect();
								connect();
							}
						}
					}
				}
				else {
					SurespotLog.v(TAG, "networkinfo null");
				}
			}
		};

	}

	// this has to be done outside of the contructor as it creates fragments, which need chat controller instance
	public void init(ViewPager viewPager, TitlePageIndicator pageIndicator, ArrayList<MenuItem> menuItems) {
		mChatPagerAdapter = new ChatPagerAdapter(mFragmentManager);
		mMenuItems = menuItems;

		mViewPager = viewPager;
		mViewPager.setAdapter(mChatPagerAdapter);
		mIndicator = pageIndicator;
		mIndicator.setViewPager(mViewPager);

		mIndicator.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
			@Override
			public void onPageSelected(int position) {
				if (mChatPagerAdapter != null) {
					SurespotLog.v(TAG, "onPageSelected, position: " + position);
					String name = mChatPagerAdapter.getChatName(position);
					setCurrentChat(name);
				}

			}
		});
		mChatPagerAdapter.setChatNames(mFriendAdapter.getActiveChats());
		onResume();
	}

	private void connect() {
		SurespotLog.v(TAG, "connect, socket: " + socket + ", connected: " + (socket != null ? socket.isConnected() : false) + ", state: "
				+ mConnectionState);

		// copy the latest ids so that we don't miss any if we receive new messages during the time we request messages and when the
		// connection completes (if they
		// are received out of order for some reason)
		//
		mPreConnectIds.clear();
		for (Entry<String, ChatAdapter> entry : mChatAdapters.entrySet()) {
			String username = entry.getKey();
			LatestIdPair idPair = new LatestIdPair();
			idPair.latestMessageId = getLatestMessageId(username);
			idPair.latestControlMessageId = getLatestMessageControlId(username);
			SurespotLog.v(TAG, "setting preconnectids for: " + username + ", latest message id:  " + idPair.latestMessageId
					+ ", latestcontrolid: " + idPair.latestControlMessageId);
			mPreConnectIds.put(username, idPair);

		}

		// if (socket != null && socket.isConnected()) {
		// return;
		// }

		Cookie cookie = IdentityController.getCookie();

		if (cookie == null) {
			// need to login
			// SurespotLog.v(TAG, "No session cookie, starting Login activity.");
			// Intent startupIntent = new Intent(mContext, StartupActivity.class);
			// startupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			// mContext.startActivity(startupIntent);
			return;
		}

		try {
			HashMap<String, String> headers = new HashMap<String, String>();
			headers.put("cookie", cookie.getName() + "=" + cookie.getValue());
			socket = new SocketIO(SurespotConfiguration.getBaseUrl(), headers);
			socket.connect(mSocketCallback);
		}
		catch (Exception e) {

			SurespotLog.w(TAG, "connect", e);
		}

	}

	private void disconnect() {
		SurespotLog.v(TAG, "disconnect.");
		setState(STATE_DISCONNECTED);

		if (socket != null) {
			socket.disconnect();
			socket = null;
		}

	}

	private void connected() {

		getFriendsAndIds();
		resendMessages();
		// MainActivity.THREAD_POOL_EXECUTOR.execute(new UpdateDataTask());

	}

	private class UpdateDataTask implements Runnable {

		@Override
		public void run() {
			getFriendsAndIds();

		}

	}

	private void resendMessages() {
		// get the resend messages
		SurespotMessage[] resendMessages = getResendMessages();
		for (SurespotMessage message : resendMessages) {
			// set the last received id so the server knows which messages to check
			String otherUser = message.getOtherUser();

			// String username = message.getFrom();
			Integer lastMessageID = 0;
			// ideally get the last id from the fragment's chat adapter
			ChatAdapter chatAdapter = mChatAdapters.get(otherUser);
			if (chatAdapter != null) {
				SurespotMessage lastMessage = chatAdapter.getLastMessageWithId();
				if (lastMessage != null) {
					lastMessageID = lastMessage.getId();
				}
			}

			// failing that use the last viewed id
			if (lastMessageID == null) {
				mFriendAdapter.getFriend(otherUser).getLastViewedMessageId();
			}

			SurespotLog.v(TAG, "setting resendId, otheruser: " + otherUser + ", id: " + lastMessageID);
			message.setResendId(lastMessageID);
			sendMessage(message);

		}
	}

	private void setOnWifi() {
		// get the initial state...sometimes when the app starts it says "hey i'm on wifi" which creates a reconnect
		ConnectivityManager connectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
		if (networkInfo != null) {
			mOnWifi = (networkInfo.getType() == ConnectivityManager.TYPE_WIFI);
		}

	}

	private void checkAndSendNextMessage(SurespotMessage message) {
		sendMessages();

		if (mResendBuffer.size() > 0) {
			if (mResendBuffer.remove(message)) {
				SurespotLog.v(TAG, "Received and removed message from resend  buffer: " + message);
			}
		}
	}

	private SurespotMessage[] getResendMessages() {
		SurespotMessage[] messages = mResendBuffer.toArray(new SurespotMessage[0]);
		mResendBuffer.clear();
		return messages;

	}

	private void sendMessages() {
		if (mBackgroundTimer == null) {
			mBackgroundTimer = new Timer("backgroundTimer");
		}

		// if (mResendTask != null) {
		// mResendTask.cancel();
		// }

		SurespotLog.v(TAG, "Sending: " + mSendBuffer.size() + " messages.");

		Iterator<SurespotMessage> iterator = mSendBuffer.iterator();
		while (iterator.hasNext()) {
			SurespotMessage message = iterator.next();
			iterator.remove();
			sendMessage(message);
		}

	}

	private void sendMessage(final SurespotMessage message) {
		mResendBuffer.add(message);
		if (getState() == STATE_CONNECTED) {
			// TODO handle different mime types
			SurespotLog.v(TAG, "sendmessage, socket: " + socket);
			JSONObject json = message.toJSONObject();
			SurespotLog.v(TAG, "sendmessage, json: " + json);
			String s = json.toString();
			SurespotLog.v(TAG, "sendmessage, message string: " + s);

			if (socket != null) {
				socket.send(s);
			}
		}
	}

	private void sendControlMessage(final SurespotControlMessage message) {
		mControlResendBuffer.add(message);
		if (getState() == STATE_CONNECTED) {

			SurespotLog.v(TAG, "sendcontrolmessage, socket: " + socket);
			JSONObject json = message.toJSONObject();
			SurespotLog.v(TAG, "sendcontrolmessage, json: " + json);
			String s = json.toString();
			SurespotLog.v(TAG, "sendcontrolmessage, message string: " + s);

			if (socket != null) {
				socket.emit("control", s);
			}
		}
	}

	private int getState() {
		return mConnectionState;
	}

	private synchronized void setState(int state) {
		mConnectionState = state;
	}

	private ReconnectTask mReconnectTask;

	private class ReconnectTask extends TimerTask {

		@Override
		public void run() {
			SurespotLog.v(TAG, "Reconnect task run.");
			connect();
		}
	}

	// private class ResendTask extends TimerTask {
	//
	// @Override
	// public void run() {
	// // resend message
	// sendMessages();
	// }
	// }
	//
	// private boolean isConnected() {
	// return (getState() == STATE_CONNECTED);
	// }

	private void updateUserMessageIds(SurespotMessage message) {
		String otherUser = message.getOtherUser();

		ChatAdapter chatAdapter = mChatAdapters.get(otherUser);

		// if the adapter is open add the message
		if (chatAdapter != null) {
			try {
				chatAdapter.addOrUpdateMessage(message, true, true, true);
			}
			catch (SurespotMessageSequenceException e) {
				SurespotLog.v(TAG, "updateUserMessageIds: " + e.getMessage());
				getLatestMessagesAndControls(otherUser, e.getMessageId());
			}
		}

		Friend friend = mFriendAdapter.getFriend(otherUser);
		if (friend != null) {
			int messageId = message.getId();

			// always update the available id
			friend.setAvailableMessageId(messageId);

			// if the chat is showing update the last viewed id
			if (otherUser.equals(mCurrentChat)) {
				friend.setLastViewedMessageId(messageId);
			}
			ChatFragment chatFragment = getChatFragment(otherUser);
			if (chatFragment != null) {
				chatFragment.scrollToEnd();
			}

			mFriendAdapter.notifyDataSetChanged();
		}
	}

	// message handling shiznit

	void loadEarlierMessages(final String username) {

		// mLoading = true;
		// get the list of messages

		Integer firstMessageId = mEarliestMessage.get(username);
		if (firstMessageId == null) {
			firstMessageId = getEarliestMessageId(username);
			mEarliestMessage.put(username, firstMessageId);
		}
		// else {
		// firstMessageId -= 60;
		// if (firstMessageId < 1) {
		// firstMessageId = 1;
		// }
		// }

		if (firstMessageId != null) {
			if (firstMessageId > 1) {

				SurespotLog.v(TAG, username + ": asking server for messages before messageId: " + firstMessageId);
				// final int fMessageId = firstMessageId;
				final ChatAdapter chatAdapter = mChatAdapters.get(username);

				MainActivity.getNetworkController().getEarlierMessages(username, firstMessageId, new JsonHttpResponseHandler() {
					@Override
					public void onSuccess(JSONArray jsonArray) {
						// on async http request, response seems to come back
						// after app is destroyed sometimes
						// (ie. on rotation on gingerbread)
						// so check for null here

						// if (getActivity() != null) {
						SurespotMessage message = null;

						try {
							for (int i = jsonArray.length() - 1; i >= 0; i--) {
								JSONObject jsonMessage = new JSONObject(jsonArray.getString(i));
								message = SurespotMessage.toSurespotMessage(jsonMessage);

								chatAdapter.insertMessage(message, false);
							}
						}
						catch (JSONException e) {
							SurespotLog.e(TAG, username + ": error creating chat message: " + e.toString(), e);
						}

						SurespotLog.v(TAG, username + ": loaded: " + jsonArray.length() + " earlier messages from the server.");

						if (message != null) {
							mEarliestMessage.put(username, message.getId());
							chatAdapter.notifyDataSetChanged();
						}
						// chatAdapter.setLoading(false);
					}

					@Override
					public void onFailure(Throwable error, String content) {
						SurespotLog.w(TAG, username + ": getEarlierMessages", error);
						// chatAdapter.setLoading(false);
					}
				});
			}
			else {
				SurespotLog.v(TAG, username + ": getEarlierMessages: no more messages.");
				// ChatFragment.this.mNoEarlierMessages = true;
			}

		}
	}

	private void getLatestIds() {
		SurespotLog.v(TAG, "getLatestIds");
		// setMessagesLoading(true);
		// gather up all the latest message IDs

		// JSONObject messageIdHolder = new JSONObject();
		// JSONObject messageIds = new JSONObject();
		// for (Entry<String, Integer> eLastReceivedId : mLastViewedMessageIds.entrySet()) {
		// try {
		// messageIds.put(ChatUtils.getSpot(IdentityController.getLoggedInUser(), eLastReceivedId.getKey()),
		// eLastReceivedId.getValue());
		// }
		// catch (JSONException e) {
		// SurespotLog.w(TAG, "loadAllLatestMessages", e);
		// }
		// }

		MainActivity.getNetworkController().getLatestIds(mLatestUserControlId, new JsonHttpResponseHandler() {

			@Override
			public void onSuccess(int statusCode, final JSONObject jsonResponse) {
				SurespotLog.v(TAG, "getlatestIds success, response: " + jsonResponse.toString() + ", statusCode: " + statusCode);

				// new AsyncTask<Void, Void, Void>() {
				// @Override
				// protected Void doInBackground(Void... params) {
				// TODO Auto-generated method stub

				JSONArray conversationIds = jsonResponse.optJSONArray("conversationIds");

				Friend friend = null;
				if (conversationIds != null) {
					for (int i = 0; i < conversationIds.length(); i++) {
						try {
							JSONObject jsonObject = conversationIds.getJSONObject(i);
							String spot = jsonObject.getString("conversation");
							Integer availableId = jsonObject.getInt("id");
							String user = ChatUtils.getOtherSpotUser(spot, IdentityController.getLoggedInUser());
							// update available ids
							friend = mFriendAdapter.getFriend(user);
							if (friend != null) {
								friend.setAvailableMessageId(availableId);
							}

						}
						catch (JSONException e) {
							SurespotLog.w(TAG, "getlatestIds", e);
						}
					}
				}

				JSONArray controlIds = jsonResponse.optJSONArray("controlIds");
				if (controlIds != null) {
					for (int i = 0; i < controlIds.length(); i++) {
						try {
							JSONObject jsonObject = controlIds.getJSONObject(i);
							String spot = jsonObject.getString("conversation");
							Integer availableId = jsonObject.getInt("id");
							String user = ChatUtils.getOtherSpotUser(spot, IdentityController.getLoggedInUser());
							// update available ids
							friend = mFriendAdapter.getFriend(user);
							if (friend != null) {
								friend.setAvailableMessageControlId(availableId);
							}
						}
						catch (JSONException e) {
							SurespotLog.w(TAG, "getlatestIds", e);
						}
					}
				}

				JSONArray userControlMessages = jsonResponse.optJSONArray("userControlMessages");
				if (userControlMessages != null) {
					handleControlMessages(IdentityController.getLoggedInUser(), userControlMessages);
				}

				if (friend != null) {

					mFriendAdapter.notifyDataSetChanged();
				}

				// Utils.makeToast(mContext, "received latest messages: " + response.toString());

				// send resend
				// resendMessages();
				// return null;
				// if (jsonArray.length() > 0) {
				// saveMessages();
				// }
				// }
				getLatestMessagesAndControls();

				// protected void onPostExecute(Void result) {

				// };
				// }.execute();
			}

			@Override
			public void onFailure(Throwable error, String content) {
				// setMessagesLoading(false);
				Utils.makeToast(mContext, "loading latest messages failed: " + content);
			}
		});

	}

	private class LatestIdPair {
		public int latestMessageId;
		public int latestControlMessageId;
	}

	private void getLatestMessagesAndControls() {
		for (Entry<String, ChatAdapter> entry : mChatAdapters.entrySet()) {
			getLatestMessagesAndControls(entry.getKey());
		}
	}

	private LatestIdPair getLatestIds(String username) {
		Friend friend = getFriendAdapter().getFriend(username);

		LatestIdPair idPair = mPreConnectIds.get(username);
		Integer latestMessageId = idPair.latestMessageId > -1 ? idPair.latestMessageId : 0;
		// if (mPreConnectIds.containsKey(username)) {

		// getLatestMessageId(username);
		int latestAvailableId = friend.getAvailableMessageId();

		int latestControlId = idPair.latestControlMessageId > -1 ? idPair.latestControlMessageId : friend.getLastReceivedMessageControlId();
		// getLatestMessageControlId(username);
		int latestAvailableControlId = friend.getAvailableMessageControlId();

		int fetchMessageId = latestAvailableId > latestMessageId ? latestMessageId : -1;
		int fetchControlMessageId = latestAvailableControlId > latestControlId ? latestControlId : -1;

		LatestIdPair intPair = new LatestIdPair();
		intPair.latestMessageId = fetchMessageId;
		intPair.latestControlMessageId = fetchControlMessageId;

		return intPair;
	}

	private void getLatestMessagesAndControls(final String username) {

		LatestIdPair ids = getLatestIds(username);

		getLatestMessagesAndControls(username, ids.latestMessageId, ids.latestControlMessageId);
	}

	private void getLatestMessagesAndControls(String username, int messageId) {
		getLatestMessagesAndControls(username, messageId, -1);

	}

	private void getLatestMessagesAndControlsSync(final String username, int fetchMessageId, int fetchControlMessageId) {

		SurespotLog.v(TAG, "getLatestMessagesAndControls: username: " + username + ", fetchMessageId: " + fetchMessageId
				+ ", fetchControlMessageId: " + fetchControlMessageId);
		if (fetchMessageId > -1 || fetchControlMessageId > -1) {

			// mChatAdapters.get(username).setLoading(true);
			String sMessageData = MainActivity.getNetworkController().getMessageDataSync(username, fetchMessageId, fetchControlMessageId);
			if (sMessageData != null) {
				try {
					JSONObject response = new JSONObject(sMessageData);
					JSONArray controlMessages = response.optJSONArray("controlMessages");
					if (controlMessages != null) {
						handleControlMessages(username, controlMessages);
						return;
					}
					String messages = response.optString("messages", null);
					if (messages != null) {
						handleMessages(username, messages);
						return;
					}
				}
				catch (JSONException e) {
					SurespotLog.w(TAG, "getLatestMessagesAndControls", e);
				}
			}
		}

		ChatAdapter chatAdapter = mChatAdapters.get(username);
		if (chatAdapter != null) {
			chatAdapter.doneCheckingSequence();
		}
		if (username.equals(mCurrentChat)) {
			ChatFragment chatFragment = getChatFragment(username);
			if (chatFragment != null) {
				chatFragment.scrollToEnd();
				chatFragment.requestFocus();

			}
		}

	}

	private void getLatestMessagesAndControls(final String username, int fetchMessageId, int fetchControlMessageId) {
		SurespotLog.v(TAG, "getLatestMessagesAndControls: fetchMessageId: " + fetchMessageId + ", fetchControlMessageId: "
				+ fetchControlMessageId);
		if (fetchMessageId > -1 || fetchControlMessageId > -1) {

			// mChatAdapters.get(username).setLoading(true);
			MainActivity.getNetworkController().getMessageData(username, fetchMessageId, fetchControlMessageId,
					new JsonHttpResponseHandler() {
						@Override
						public void onSuccess(int statusCode, JSONObject response) {

							JSONArray controlMessages = response.optJSONArray("controlMessages");
							if (controlMessages != null) {
								handleControlMessages(username, controlMessages);
							}
							String messages = response.optString("messages", null);
							if (messages != null) {
								handleMessages(username, messages);
							}

						}
					});
		}
		else {
			if (username.equals(mCurrentChat)) {
				ChatFragment chatFragment = getChatFragment(username);
				if (chatFragment != null) {
					chatFragment.scrollToEnd();
					chatFragment.requestFocus();

				}
			}
		}
	}

	private void handleControlMessages(String username, JSONArray jsonArray) {
		SurespotLog.v(TAG, username + ": handleControlMessages");
		final ChatAdapter chatAdapter = mChatAdapters.get(username);

		SurespotControlMessage message = null;
		boolean messageActivity = false;
		boolean userActivity = false;
		for (int i = 0; i < jsonArray.length(); i++) {
			try {
				JSONObject jsonMessage = new JSONObject(jsonArray.getString(i));
				message = SurespotControlMessage.toSurespotControlMessage(jsonMessage);

				// if it's a system message from another user then check version
				if (message.getType().equals("user")) {
					mLatestUserControlId = message.getId();
					userActivity = true;
					if (message.getAction().equals("revoke")) {
						IdentityController.updateLatestVersion(mContext, message.getData(), message.getMoreData());
					}
					else if (message.getAction().equals("invited")) {
						mFriendAdapter.addFriendInvited(message.getData());
					}
					else if (message.getAction().equals("added")) {
						mFriendAdapter.addNewFriend(message.getData());
					}
					else if (message.getAction().equals("invite")) {
						mFriendAdapter.addFriendInviter(message.getData());
					}
					else if (message.getAction().equals("decline")) {
						mFriendAdapter.removeFriend(message.getData());
					}

				}

				else if (message.getType().equals("message")) {
					messageActivity = true;
					SurespotMessage dMessage = chatAdapter.getMessageById(Integer.parseInt(message.getMoreData()));
					if (dMessage != null) {
						if (message.getAction().equals("delete")) {

							// if it's an image blow the http cache entry away
							if (dMessage.getMimeType() != null) {
								if (dMessage.getMimeType().equals(SurespotConstants.MimeTypes.IMAGE)) {
									MainActivity.getNetworkController().purgeCacheUrl(dMessage.getData());
								}

								boolean controlFromMe = message.getFrom().equals(IdentityController.getLoggedInUser());
								boolean myMessage = dMessage.getFrom().equals(IdentityController.getLoggedInUser());

								// if i sent the delete, or it's not my message then delete it
								// (if someone else deleted my message we don't care)
								if (controlFromMe || !myMessage) {
									SurespotLog.v(TAG, "deleting message");
									chatAdapter.deleteMessageById(dMessage.getId());
								}

							}
						}
						else {
							if (message.getAction().equals("shareable") || message.getAction().equals("notshareable")) {
								SurespotLog.v(TAG, "setting message " + message.getAction());
								dMessage.setShareable(message.getAction().equals("shareable") ? true : false);
							}
						}
					}
				}

			}
			catch (JSONException e) {
				SurespotLog.w(TAG, username + ": error creating chat message: " + e.toString(), e);
			}

		}

		if (message != null) {

			SurespotLog.v(TAG, username + ": loaded: " + jsonArray.length() + " latest control messages from the server.");

			if (messageActivity) {
				mFriendAdapter.getFriend(username).setLastReceivedMessageControlId(message.getId());
				mFriendAdapter.notifyDataSetChanged();
				chatAdapter.sort();
				chatAdapter.notifyDataSetChanged();
			}

			if (userActivity) {
				mFriendAdapter.notifyDataSetChanged();
			}
		}

		// chatAdapter.setLoading(false);
	}

	private void handleMessages(String username, String jsonMessageString) {
		SurespotLog.v(TAG, username + ": handleMessages");
		final ChatAdapter chatAdapter = mChatAdapters.get(username);
		int sentByMeCount = 0;

		SurespotMessage lastMessage = null;
		try {
			JSONArray jsonUM = new JSONArray(jsonMessageString);
			SurespotLog.v(TAG, username + ": loaded: " + jsonUM.length() + " messages from the server: " + jsonMessageString);
			for (int i = 0; i < jsonUM.length(); i++) {
				lastMessage = SurespotMessage.toSurespotMessage(new JSONObject(jsonUM.getString(i)));
				boolean added = chatAdapter.addOrUpdateMessage(lastMessage, false, false, false);
				mResendBuffer.remove(lastMessage);
				if (added & lastMessage.getFrom().equals(IdentityController.getLoggedInUser())) {
					sentByMeCount++;
				}

			}
		}
		catch (JSONException e) {
			SurespotLog.w(TAG, "jsonStringsToMessages", e);

		}
		catch (SurespotMessageSequenceException e) {
			// shouldn't happen
			SurespotLog.w(TAG, "handleMessages", e);
			// getLatestMessagesAndControls(username, e.getMessageId(), -1);
			return;
		}

		if (lastMessage != null) {
			Friend friend = mFriendAdapter.getFriend(username);

			int availableId = lastMessage.getId();
			friend.setAvailableMessageId(availableId);

			int adjustedLastViewedId = friend.getLastViewedMessageId() + sentByMeCount;
			friend.setLastViewedMessageId(adjustedLastViewedId);

			chatAdapter.sort();
			chatAdapter.notifyDataSetChanged();
			chatAdapter.doneCheckingSequence();
			mFriendAdapter.notifyDataSetChanged();

		}

		if (username.equals(mCurrentChat)) {
			ChatFragment chatFragment = getChatFragment(username);
			if (chatFragment != null) {
				chatFragment.scrollToEnd();
				chatFragment.requestFocus();

			}
		}
	}

	// tell the chat adapters we've loaded their data (even if they didn't have any)
	public void setMessagesLoading(boolean loading) {
		for (ChatAdapter ca : mChatAdapters.values()) {
			ca.setLoading(loading);
		}
	}

	private Integer getEarliestMessageId(String username) {

		ChatAdapter chatAdapter = mChatAdapters.get(username);
		Integer firstMessageId = null;
		if (chatAdapter != null) {
			SurespotMessage firstMessage = chatAdapter.getFirstMessageWithId();

			if (firstMessage != null) {
				firstMessageId = firstMessage.getId();
			}

		}
		return firstMessageId;
	}

	private int getLatestMessageId(String username) {
		Integer lastMessageId = 0;
		ChatAdapter chatAdapter = mChatAdapters.get(username);
		if (chatAdapter != null) {

			SurespotMessage lastMessage = chatAdapter.getLastMessageWithId();
			if (lastMessage != null) {
				lastMessageId = lastMessage.getId();
			}
		}
		return lastMessageId;

	}

	private Integer getLatestMessageControlId(String username) {
		Friend friend = mFriendAdapter.getFriend(username);
		Integer lastControlId = null;
		if (friend != null) {
			lastControlId = friend.getLastReceivedMessageControlId();
		}
		return lastControlId == null ? 0 : lastControlId;
	}

	private synchronized void loadMessages(String username) {
		SurespotLog.v(TAG, "loadMessages: " + username);
		String spot = ChatUtils.getSpot(IdentityController.getLoggedInUser(), username);
		ChatAdapter chatAdapter = mChatAdapters.get(username);
		chatAdapter.setMessages(SurespotApplication.getStateController().loadMessages(spot));
		// ChatFragment chatFragment = getChatFragment(username);
	}

	private synchronized void saveMessages() {
		// save last 30? messages
		SurespotLog.v(TAG, "saveMessages");
		if (IdentityController.getLoggedInUser() != null) {
			for (Entry<String, ChatAdapter> entry : mChatAdapters.entrySet()) {
				String them = entry.getKey();
				String spot = ChatUtils.getSpot(IdentityController.getLoggedInUser(), them);
				SurespotApplication.getStateController().saveMessages(spot, entry.getValue().getMessages());
			}
		}
	}

	private synchronized void saveMessages(String username) {
		// save last 30? messages
		SurespotLog.v(TAG, "saveMessages, username:" + username);
		ChatAdapter chatAdapter = mChatAdapters.get(username);

		if (chatAdapter != null) {
			SurespotApplication.getStateController().saveMessages(ChatUtils.getSpot(IdentityController.getLoggedInUser(), username),
					chatAdapter.getMessages());
		}

	}

	private void saveUnsentMessages() {
		mResendBuffer.addAll(mSendBuffer);
		// SurespotLog.v(TAG, "saving: " + mResendBuffer.size() + " unsent messages.");
		SurespotApplication.getStateController().saveUnsentMessages(mResendBuffer);
	}

	private void loadUnsentMessages() {
		Iterator<SurespotMessage> iterator = SurespotApplication.getStateController().loadUnsentMessages().iterator();
		while (iterator.hasNext()) {
			mResendBuffer.add(iterator.next());
		}
		// SurespotLog.v(TAG, "loaded: " + mSendBuffer.size() + " unsent messages.");
	}

	public synchronized void logout() {
		mCurrentChat = null;
		onPause();
		// mViewPager = null;
		// mCallback401 = null;
		// mChatPagerAdapter = null;
		// mIndicator = null;
		// mFragmentManager = null;
		// mFriendAdapter = null;
		// mMenuItems = null;
		// mSocketCallback = null;
		mChatAdapters.clear();
		// mActiveChats.clear();
		// mReadSinceConnected.clear();
		mResendBuffer.clear();
		mSendBuffer.clear();
	}

	private void saveState(String username) {

		SurespotLog.v(TAG, "saveState");

		if (username == null) {
			saveUnsentMessages();
			saveMessages();
			SurespotLog.v(TAG, "saving last chat: " + mCurrentChat);
			Utils.putSharedPrefsString(mContext, SurespotConstants.PrefNames.LAST_CHAT, mCurrentChat);
			SurespotApplication.getStateController().saveFriends(mLatestUserControlId, mFriendAdapter.getFriends());
		}
		else {
			saveMessages(username);
		}

		// SurespotApplication.getStateController().saveActiveChats(mActiveChats);
		// SurespotApplication.getStateController().saveLastReceivedMessageIds(mLastViewedMessageIds);
		// SurespotApplication.getStateController().saveMessageActivity(mReadSinceConnected);

	}

	private void loadState() {
		SurespotLog.v(TAG, "loadState");

		// mActiveChats = SurespotApplication.getStateController().loadActiveChats();
		// mReadSinceConnected = SurespotApplication.getStateController().loadMessageActivity();
		FriendState fs = SurespotApplication.getStateController().loadFriends();

		List<Friend> friends = null;
		if (fs != null) {
			mLatestUserControlId = fs.userControlId;
			friends = fs.friends;
		}

		mFriendAdapter.setFriends(friends);
		mFriendAdapter.setLoading(false);

		// mLastViewedMessageIds = SurespotApplication.getStateController().loadLastReceivedMessageIds();
		// mLastReceivedControlIds = SurespotApplication.getStateController().loadLastReceivedControlIds();

		loadUnsentMessages();
	}

	public synchronized void onResume() {
		SurespotLog.v(TAG, "onResume, mPaused: " + mPaused + ": " + this);
		if (mPaused) {
			mPaused = false;

			// Set<String> names = SurespotApplication.getStateController().loadFriends();
			// if (names != null && names.size() > 0) {
			// mFriendAdapter.setFriendNames(names);
			// mFriendAdapter.setLoading(false);
			// }

			connect();
			mContext.registerReceiver(mConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
		}
	}

	public synchronized void onPause() {
		SurespotLog.v(TAG, "onPause, mPaused: " + mPaused + ": " + this);
		if (!mPaused) {
			mPaused = true;
			saveState(null);
		}

		disconnect();

		if (mBackgroundTimer != null) {
			mBackgroundTimer.cancel();
			mBackgroundTimer = null;
		}
		if (mReconnectTask != null) {
			boolean cancel = mReconnectTask.cancel();
			mReconnectTask = null;
			SurespotLog.v(TAG, "Cancelled reconnect task: " + cancel);
		}

		// socket = null;

		// workaround unchecked exception: https://code.google.com/p/android/issues/detail?id=18147
		try {
			mContext.unregisterReceiver(mConnectivityReceiver);
		}
		catch (IllegalArgumentException e) {
			if (e.getMessage().contains("Receiver not registered")) {
				// Ignore this exception. This is exactly what is desired
			}
			else {
				// unexpected, re-throw
				throw e;
			}
		}
		// }

	}

	ChatAdapter getChatAdapter(Context context, String username) {

		ChatAdapter chatAdapter = mChatAdapters.get(username);
		if (chatAdapter == null) {

			chatAdapter = new ChatAdapter(context);
			SurespotLog.v(TAG, "getChatAdapter created chat adapter for: " + username + ", id:  " + chatAdapter);
			mChatAdapters.put(username, chatAdapter);

			// load savedmessages
			loadMessages(username);

			LatestIdPair idPair = new LatestIdPair();
			idPair.latestMessageId = getLatestMessageId(username);
			idPair.latestControlMessageId = getLatestMessageControlId(username);
			SurespotLog.v(TAG, "setting preconnectids for: " + username + ", latest message id:  " + idPair.latestMessageId
					+ ", latestcontrolid: " + idPair.latestControlMessageId);
			mPreConnectIds.put(username, idPair);

			// get latest messages from server
			getLatestMessagesAndControls(username);

		}
		return chatAdapter;
	}

	public void destroyChatAdapter(String username) {
		SurespotLog.v(TAG, "destroying chat adapter for: " + username);
		saveState(username);
		mChatAdapters.remove(username);
	}

	public synchronized void setCurrentChat(final String username) {

		SurespotLog.v(TAG, username + ": setCurrentChat");
		String loggedInUser = IdentityController.getLoggedInUser();
		if (loggedInUser == null) {
			return;
		}
		mCurrentChat = username;
		if (username != null) {
			Friend friend = mFriendAdapter.getFriend(username);
			if (friend != null) {
				mChatPagerAdapter.addChatName(username);
				friend.setChatActive(true);
				friend.setLastViewedMessageId(friend.getAvailableMessageId());
				mFriendAdapter.sort();
				mFriendAdapter.notifyDataSetChanged();

				// cancel associated notifications
				mNotificationManager.cancel(ChatUtils.getSpot(loggedInUser, username),
						SurespotConstants.IntentRequestCodes.NEW_MESSAGE_NOTIFICATION);
				int wantedPosition = mChatPagerAdapter.getChatFragmentPosition(username);

				if (wantedPosition != mViewPager.getCurrentItem()) {
					mViewPager.setCurrentItem(wantedPosition, true);
				}

				ChatFragment chatFragment = getChatFragment(username);
				if (chatFragment != null) {
					chatFragment.requestFocus();
				}

				if (mMode == MODE_SELECT) {
					chatFragment.handleSendIntent();
					setMode(MODE_NORMAL);
				}
			}
		}
		else {
			mViewPager.setCurrentItem(0, true);
			mNotificationManager.cancel(loggedInUser, SurespotConstants.IntentRequestCodes.INVITE_REQUEST_NOTIFICATION);
			mNotificationManager.cancel(loggedInUser, SurespotConstants.IntentRequestCodes.INVITE_RESPONSE_NOTIFICATION);

		}
		// disable menu items
		enableMenuItems();

	}

	private ChatFragment getChatFragment(String username) {
		String fragmentTag = Utils.makePagerFragmentName(mViewPager.getId(), username.hashCode());
		SurespotLog.v(TAG, "looking for fragment: " + fragmentTag);
		ChatFragment chatFragment = (ChatFragment) mFragmentManager.findFragmentByTag(fragmentTag);
		SurespotLog.v(TAG, "fragment: " + chatFragment);
		return chatFragment;
	}

	void sendMessage(final String username, final String plainText, final String mimeType) {
		if (plainText.length() > 0) {

			// display the message immediately
			final byte[] iv = EncryptionController.getIv();

			// build a message without the encryption values set as they could take a while
			final SurespotMessage chatMessage = ChatUtils.buildPlainMessage(username, mimeType, plainText,
					new String(Utils.base64Encode(iv)));
			ChatAdapter chatAdapter = mChatAdapters.get(username);

			try {
				chatAdapter.addOrUpdateMessage(chatMessage, false, true, true);
			}
			catch (SurespotMessageSequenceException e) {
				// not gonna happen
				SurespotLog.v(TAG, "sendMessage", e);
			}

			// do encryption in background
			new AsyncTask<Void, Void, SurespotMessage>() {

				@Override
				protected SurespotMessage doInBackground(Void... arg0) {
					String ourLatestVersion = IdentityController.getOurLatestVersion();
					String theirLatestVersion = IdentityController.getTheirLatestVersion(username);

					String result = EncryptionController.symmetricEncrypt(ourLatestVersion, username, theirLatestVersion, plainText, iv);

					if (result != null) {
						chatMessage.setData(result);
						chatMessage.setFromVersion(ourLatestVersion);
						chatMessage.setToVersion(theirLatestVersion);
						ChatController.this.sendMessage(chatMessage);
					}

					return null;
				}

				// protected void onPostExecute(SurespotMessage result) {
				// if (result != null) {
				//
				//
				// }
				//
				// };
			}.execute();
		}

	}

	public static String getCurrentChat() {
		return mCurrentChat;
	}

	public static boolean isPaused() {
		return mPaused;
	}

	public boolean hasEarlierMessages(String username) {
		Integer id = mEarliestMessage.get(username);
		if (id == null) {
			id = getEarliestMessageId(username);
		}

		if (id != null && id > 1) {
			return true;
		}

		return false;
	}

	public void deleteMessage(SurespotMessage message) {
		// if it's on the server, send delete control message otherwise just delete it locally
		if (message.getId() != null) {
			SurespotControlMessage dmessage = new SurespotControlMessage();
			String me = IdentityController.getLoggedInUser();
			dmessage.setFrom(me);
			dmessage.setType("message");
			dmessage.setAction("delete");
			dmessage.setData(ChatUtils.getSpot(message));
			dmessage.setMoreData(message.getId().toString());
			dmessage.setLocalId(me + Integer.toString(getLatestMessageControlId(message.getOtherUser()) + 1));

			sendControlMessage(dmessage);
		}
		else {
			String otherUser = message.getOtherUser();
			mResendBuffer.remove(message);
			mSendBuffer.remove(message);
			ChatAdapter chatAdapter = mChatAdapters.get(otherUser);

			chatAdapter.deleteMessageByIv(message.getIv());
			saveState(otherUser);

		}
	}

	public void setMessageShareable(Context context, String username, int id, boolean shareable) {
		SurespotMessage message = getChatAdapter(context, username).getMessageById(id);
		if (message != null && message.isShareable() != shareable) {
			SurespotControlMessage dmessage = new SurespotControlMessage();
			String me = IdentityController.getLoggedInUser();
			dmessage.setFrom(me);
			dmessage.setType("message");
			dmessage.setAction(shareable ? "shareable" : "notshareable");
			dmessage.setData(ChatUtils.getSpot(message));
			dmessage.setMoreData(message.getId().toString());
			dmessage.setLocalId(me + Integer.toString(getLatestMessageControlId(message.getOtherUser()) + 1));

			sendControlMessage(dmessage);
		}
	}

	public FriendAdapter getFriendAdapter() {
		return mFriendAdapter;
	}

	private void getFriendsAndIds() {
		if (mFriendAdapter.getCount() == 0 && mLatestUserControlId == 0) {
			mFriendAdapter.setLoading(true);
			// get the list of friends
			MainActivity.getNetworkController().getFriends(new JsonHttpResponseHandler() {
				@Override
				public void onSuccess(JSONObject jsonObject) {
					SurespotLog.v(TAG, "getFriends success.");
					ArrayList<Friend> friends = new ArrayList<Friend>();
					try {
						mLatestUserControlId = jsonObject.getInt("userControlId");
						JSONArray jsonArray = jsonObject.getJSONArray("friends");

						for (int i = 0; i < jsonArray.length(); i++) {
							JSONObject jsonFriend = jsonArray.getJSONObject(i);
							Friend friend = Friend.toFriend(jsonFriend);
							friends.add(friend);
						}
					}
					catch (JSONException e) {
						SurespotLog.e(TAG, e.toString(), e);
						mFriendAdapter.setLoading(false);
						return;
					}

					if (mFriendAdapter != null) {
						mFriendAdapter.addFriends(friends);
						mFriendAdapter.setLoading(false);
					}

					getLatestIds();
				}

				@Override
				public void onFailure(Throwable arg0, String content) {
					// if we didn't get a 401
					if (!MainActivity.getNetworkController().isUnauthorized()) {
						mFriendAdapter.setLoading(false);
						SurespotLog.w(TAG, "getFriends: " + content, arg0);
					}
				}
			});
		}
		else {
			getLatestIds();
		}
	}

	public void closeTab() {
		if (mChatPagerAdapter.getCount() > 0) {

			int position = mViewPager.getCurrentItem();
			if (position > 0) {

				String name = mChatPagerAdapter.getChatName(position);
				SurespotLog.v(TAG, "closeTab, name: " + name + ", position: " + position);

				mChatPagerAdapter.removeChat(mViewPager.getId(), position);
				mFriendAdapter.setChatActive(name, false);
				mEarliestMessage.remove(name);
				destroyChatAdapter(name);
				mIndicator.notifyDataSetChanged();

				position = mViewPager.getCurrentItem();
				mCurrentChat = mChatPagerAdapter.getChatName(position);

				SurespotLog.v(TAG, "closeTab, new tab name: " + mCurrentChat + ", position: " + position);
			}
		}
	}

	public synchronized void setMode(int mode) {
		mMode = mode;
	}

	public int getMode() {
		return mMode;
	}

	public void enableMenuItems() {
		boolean enabled = mMode != MODE_SELECT && mCurrentChat != null;
		SurespotLog.v(TAG, "enableMenuItems, enabled: " + enabled);
		if (mMenuItems != null) {
			for (MenuItem menuItem : mMenuItems) {
				menuItem.setVisible(enabled);
			}
		}
	}

}
