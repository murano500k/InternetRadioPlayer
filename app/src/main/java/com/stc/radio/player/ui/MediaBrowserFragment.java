package com.stc.radio.player.ui;

import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.FragmentActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.mikepenz.fastadapter.FastAdapter;
import com.mikepenz.fastadapter.IAdapter;
import com.mikepenz.fastadapter.adapters.FastItemAdapter;
import com.stc.radio.player.R;
import com.stc.radio.player.model.MediaBrowserProvider;
import com.stc.radio.player.source.MusicProviderSource;
import com.stc.radio.player.ui.customviews.AutoFitGridRecyclerView;
import com.stc.radio.player.utils.ListColumnsCounter;
import com.stc.radio.player.utils.LogHelper;
import com.stc.radio.player.utils.MediaIDHelper;
import com.stc.radio.player.utils.NetworkHelper;

import java.util.List;

import timber.log.Timber;


public class MediaBrowserFragment extends Fragment{
	private static final String TAG = LogHelper.makeLogTag(MediaBrowserFragment.class);

	private static final String ARG_MEDIA_ID = "media_id";
	private String mMediaId;
	private MediaFragmentListener mListener;
	private FastItemAdapter<MediaListItem> fastItemAdapter;
	private RecyclerView recyclerView;
	private View mErrorView;
	private TextView mErrorMessage;
	private ProgressBar progressBar;
	public MediaBrowserFragment() {

	}

	public void onScrollToItem(String query) {
		for(MediaListItem listItem :    fastItemAdapter.getAdapterItems()){
			String title=listItem.getMediaItem().getDescription().getTitle().toString();
			if (title.equalsIgnoreCase(query)){
				recyclerView.smoothScrollToPosition(
						fastItemAdapter.getAdapterPosition(listItem)
				);
			}
		}
	}

	private final BroadcastReceiver mConnectivityChangeReceiver = new BroadcastReceiver() {
		private boolean oldOnline = false;
		@Override
		public void onReceive(Context context, Intent intent) {
			if (mMediaId != null) {
				boolean isOnline =  NetworkHelper.isOnline(context);
				if (isOnline != oldOnline) {
					oldOnline = isOnline;
					checkForUserVisibleErrors(false);
					if (isOnline) {
						fastItemAdapter.notifyDataSetChanged();
					}else Toast.makeText(getActivity(), "No connection", Toast.LENGTH_SHORT).show();
				}
			}
		}
	};

	private void updateCastData(android.media.MediaMetadata selectedMedia) {
		com.google.android.gms.cast.MediaMetadata movieMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);

		movieMetadata.putString(MediaMetadata.KEY_TITLE, selectedMedia.getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
		movieMetadata.addImage(new WebImage(Uri.parse(selectedMedia.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI))));
		MediaInfo mediaInfo = new MediaInfo.Builder(selectedMedia.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE))
				.setStreamType(MediaInfo.STREAM_TYPE_LIVE)
				.setContentType("audio/*")
				.setMetadata(movieMetadata)
				.setStreamDuration(-1)
				.build();

		RemoteMediaClient remoteMediaClient = ((MusicPlayerActivity) getActivity())
				.getSessionManager().getCurrentCastSession().getRemoteMediaClient();
		remoteMediaClient.load(mediaInfo);
	}

	private final MediaController.Callback mMediaControllerCallback =
			new MediaController.Callback() {
				@Override
				public void onMetadataChanged(android.media.MediaMetadata metadata) {
					super.onMetadataChanged(metadata);
					if (metadata == null) {
						return;
					}
					LogHelper.d(TAG, "Received metadata change to media ",
							metadata.getDescription().getMediaId());
					fastItemAdapter.notifyDataSetChanged();
					onScrollToItem((String) metadata.getText(android.media.MediaMetadata.METADATA_KEY_TITLE));

				}

				@Override
				public void onPlaybackStateChanged(@NonNull PlaybackState state) {
					super.onPlaybackStateChanged(state);
					LogHelper.d(TAG, "Received state change: ", state);
					if(state.getState()==PlaybackState.STATE_ERROR
							&& state.getErrorMessage()!=null){
						checkForUserVisibleErrors(true);
					}else checkForUserVisibleErrors(false);
					fastItemAdapter.notifyDataSetChanged();
				}
			};

	private final MediaBrowser.SubscriptionCallback mSubscriptionCallback =
			new MediaBrowser.SubscriptionCallback() {
				@Override
				public void onChildrenLoaded(@NonNull String parentId,
				                             @NonNull List<MediaBrowser.MediaItem> children) {
					try {
						progressBar.setVisibility(View.GONE);
						Timber.w("child hierarchy : %s", children.get(0).getMediaId());
						checkForUserVisibleErrors(children.isEmpty());
						fastItemAdapter.clear();
						for (MediaBrowser.MediaItem item : children) {
							int itemState = MediaListItem.STATE_NONE;
							boolean isFavorite=false;
							String musicId = MediaIDHelper.extractMusicIDFromMediaID(
									item.getDescription().getMediaId());
							//isFavorite= RatingHelper.isFavorite(musicId);
							if(isFavorite)Timber.w("%s isFav %b", musicId, isFavorite);
							if (item.isPlayable()) {
								itemState = MediaListItem.STATE_PLAYABLE;
								MediaController controller = ((FragmentActivity) getActivity())
										.getMediaController();
								if (controller != null && controller.getMetadata() != null) {
									String currentPlaying = controller.getMetadata().getDescription().getMediaId();

									if (currentPlaying != null && currentPlaying.equals(musicId)) {
										PlaybackState pbState = controller.getPlaybackState();
										if (pbState == null ||
												pbState.getState() == PlaybackState.STATE_ERROR) {
											itemState = MediaListItem.STATE_NONE;
										} else if (pbState.getState() == PlaybackState.STATE_PLAYING) {
											itemState = MediaListItem.STATE_PLAYING;
										} else {
											itemState = MediaListItem.STATE_PAUSED;
										}

									}
								}
							}
							fastItemAdapter.add(new MediaListItem(
									item,
									itemState,
									getActivity()
							));
						}
						fastItemAdapter.notifyDataSetChanged();
						progressBar.setVisibility(View.GONE);

					} catch (Throwable t) {
						LogHelper.e(TAG, "Error on childrenloaded", t);
					}
				}

				@Override
				public void onError(@NonNull String id) {
					LogHelper.e(TAG, "browse fragment subscription onError, id=" + id);
					Toast.makeText(getActivity(), R.string.error_loading_media, Toast.LENGTH_LONG).show();
					checkForUserVisibleErrors(true);
				}
			};


	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		if (context instanceof MediaFragmentListener) {
			mListener = (MediaFragmentListener) context;
		} else {
			throw new RuntimeException(context.toString()
					+ " must implement OnListFragmentInteractionListener");
		}
	}



	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
	                         Bundle savedInstanceState) {
		setRetainInstance(true);
		View view = inflater.inflate(R.layout.fragment_list_uamp, container, false);
		mErrorView = view.findViewById(R.id.playback_error);
		mErrorMessage = (TextView) mErrorView.findViewById(R.id.error_message);
		Context context = view.getContext();
		progressBar=(ProgressBar)view.findViewById(R.id.progress_bar);
		recyclerView = (AutoFitGridRecyclerView)view.findViewById(R.id.recyclerViewAutoFit);
		recyclerView.setItemAnimator(new DefaultItemAnimator());
		recyclerView.setLayoutManager(new GridLayoutManager(context, ListColumnsCounter.calculateNoOfColumns(context)));
		fastItemAdapter = new FastItemAdapter<>();
		fastItemAdapter.withSelectable(false);
		fastItemAdapter.withMultiSelect(false);
		fastItemAdapter.select(true);
		fastItemAdapter.withOnClickListener(listItemOnClickListener);
		recyclerView.setAdapter(fastItemAdapter);

		return view;
	}

	@Override
	public void onStart() {
		super.onStart();
        if(mListener !=null){
            MediaBrowser mediaBrowser = mListener.getMediaBrowser();
            if (mediaBrowser.isConnected()) {
                LogHelper.d(TAG, "fragment.onStart, mediaId=", mMediaId,
                        "  onConnected=" + mediaBrowser.isConnected());
                onConnected();
                progressBar.setVisibility(View.GONE);
            }else if(fastItemAdapter!=null && fastItemAdapter.getAdapterItemCount()>1) {
                Log.d(TAG, "onStart: list not empty");

			}else {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

		this.getActivity().registerReceiver(mConnectivityChangeReceiver,
				new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	@Override
	public void onStop() {
		super.onStop();
		if(mListener!=null) {
			MediaBrowser mediaBrowser = mListener.getMediaBrowser();
			if (mediaBrowser != null && mediaBrowser.isConnected() && mMediaId != null) {
				mediaBrowser.unsubscribe(mMediaId);
			}
		}
		MediaController controller = ((FragmentActivity) getActivity())
				.getMediaController();
		if (controller != null) {
			controller.unregisterCallback(mMediaControllerCallback);
		}
		this.getActivity().unregisterReceiver(mConnectivityChangeReceiver);
	}
	@Override
	public void onDetach() {
		super.onDetach();
		mListener = null;
	}
	public String getMediaId() {
		Bundle args = getArguments();
		if (args != null) {
			return args.getString(ARG_MEDIA_ID);
		}
		return null;
	}



	public void setMediaId(String mediaId) {
		Bundle args = new Bundle(1);
		args.putString(MediaBrowserFragment.ARG_MEDIA_ID, mediaId);
		setArguments(args);
	}

	public void onConnected() {
		if (isDetached()) {
			return;
		}
		mMediaId = getMediaId();
		if (mMediaId == null) {
			mMediaId = mListener.getMediaBrowser().getRoot();
		}
		updateTitle();
		mListener.getMediaBrowser().unsubscribe(mMediaId);

		mListener.getMediaBrowser().subscribe(mMediaId, mSubscriptionCallback);
		MediaController controller = ((FragmentActivity) getActivity())
				.getMediaController();
		if (controller != null) {
			controller.registerCallback(mMediaControllerCallback);
		}
	}

	private void checkForUserVisibleErrors(boolean forceError) {
		Log.d(TAG, "checkForUserVisibleErrors() called with: forceError = [" + forceError + "]");

		boolean showError = forceError;
		// If offline, message is about the lack of connectivity:
		if (!NetworkHelper.isOnline(getActivity())) {
			mErrorMessage.setText(R.string.error_no_connection);
			showError = true;
		} else {
			// otherwise, if state is ERROR and metadata!=null, use playback state error message:
			MediaController controller = ((FragmentActivity) getActivity())
					.getMediaController();
			if (controller != null
					&& controller.getMetadata() != null
					&& controller.getPlaybackState() != null
					&& controller.getPlaybackState().getState() == PlaybackState.STATE_ERROR
					&& controller.getPlaybackState().getErrorMessage() != null) {
				mErrorMessage.setText(controller.getPlaybackState().getErrorMessage());
				showError = true;
			} else if (forceError) {
				// Finally, if the caller requested to show error, show a generic message:
				mErrorMessage.setText(R.string.error_loading_media);
				showError = true;
			}
		}
		mErrorView.setVisibility(showError ? View.VISIBLE : View.GONE);
		if(showError) {
			String stringError="ERROR";
			if(mErrorMessage!=null && mErrorMessage.getText()!=null && mErrorMessage.getText().length()>0){
				stringError=mErrorMessage.getText().toString();
			}
			Toast.makeText(getActivity(), stringError, Toast.LENGTH_SHORT).show();
		}
		LogHelper.d(TAG, "checkForUserVisibleErrors. forceError=", forceError,
				" showError=", showError,
				" isOnline=", NetworkHelper.isOnline(getActivity()));
	}

	private void updateTitle() {
		if (MediaIDHelper.MEDIA_ID_ROOT.equals(mMediaId)) {
			mListener.setToolbarTitle(null);
			return;
		}

		MediaBrowser mediaBrowser = mListener.getMediaBrowser();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			mediaBrowser.getItem(mMediaId, new MediaBrowser.ItemCallback() {
                @Override
                public void onItemLoaded(MediaBrowser.MediaItem item) {
                    mListener.setToolbarTitle(
                            item.getDescription().getTitle());
                }
            });
		}
	}

	public interface MediaFragmentListener extends MediaBrowserProvider {
		void onMediaItemSelected(MediaBrowser.MediaItem item);
		void setToolbarTitle(CharSequence title);
		void isItemFavorite(String musicId);
	}

	public FastItemAdapter.OnClickListener<MediaListItem> listItemOnClickListener=new FastAdapter.OnClickListener<MediaListItem>() {
		@Override
		public boolean onClick(View v, IAdapter<MediaListItem> adapter, MediaListItem item, int position) {
			Timber.v("item %s",item.getName());
			checkForUserVisibleErrors(false);
			MediaBrowser.MediaItem mediaItem = fastItemAdapter.getItem(position).getMediaItem();
			mListener.onMediaItemSelected(mediaItem);
			return true;
		}
	};
}
