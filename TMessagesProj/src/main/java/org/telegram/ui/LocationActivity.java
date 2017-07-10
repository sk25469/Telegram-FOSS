/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.ui;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.osmdroid.api.IMapController;
import org.osmdroid.views.MapView;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.support.widget.LinearLayoutManager;
import org.telegram.messenger.support.widget.RecyclerView;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.LocationActivityAdapter;
import org.telegram.ui.Cells.LocationCell;
import org.telegram.ui.Cells.SendLocationCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MapPlaceholderDrawable;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;
import java.util.Locale;

public class LocationActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private TextView distanceTextView;
    private BackupImageView avatarImageView;
    private TextView nameTextView;
    private MapView mapView;
    private FrameLayout mapViewClip;
    private LocationActivityAdapter adapter;
    private RecyclerListView listView;
    private ImageView markerImageView;
    private ImageView markerXImageView;
    private ImageView locationButton;
    private ImageView routeButton;
    private FrameLayout bottomView;
    private LinearLayoutManager layoutManager;
    private AvatarDrawable avatarDrawable;
    private TextView attributionOverlay;

    private AnimatorSet animatorSet;

    private boolean checkPermission = true;

    private boolean mapsInitialized;

    private Location myLocation;
    private Location userLocation;
    private int markerTop;

    private MessageObject messageObject;
    private boolean userLocationMoved = false;
    private boolean firstWas = false;
    private LocationActivityDelegate delegate;

    private int overScrollHeight = AndroidUtilities.displaySize.x - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.dp(66);

    private final static int share = 1;
    /* TFOSS: TileSources
    private final static int map_list_menu_map = 2;
    private final static int map_list_menu_satellite = 3;
    private final static int map_list_menu_hybrid = 4;
    */

    private MyLocationNewOverlay myLocationOverlay;

    public interface LocationActivityDelegate {
        void didSelectLocation(TLRPC.MessageMedia location);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        swipeBackEnabled = false;
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.locationPermissionGranted);
        if (messageObject != null) {
            NotificationCenter.getInstance().addObserver(this, NotificationCenter.updateInterfaces);
        }
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.locationPermissionGranted);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.closeChats);
        if (adapter != null) {
            adapter.destroy();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setAddToContainer(messageObject != null);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }/* TFOSS: TileSources https://github.com/osmdroid/osmdroid/wiki/Map-Sources
                else if (id == map_list_menu_map) {
                    if (googleMap != null) {
                        googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                    }
                } else if (id == map_list_menu_satellite) {
                    if (googleMap != null) {
                        googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                    }
                } else if (id == map_list_menu_hybrid) {
                    if (googleMap != null) {
                        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    }
                }*/ else if (id == share) {
                    try {
                        double lat = messageObject.messageOwner.media.geo.lat;
                        double lon = messageObject.messageOwner.media.geo._long;
                        getParentActivity().startActivity(new Intent(android.content.Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon)));
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        if (messageObject != null) {
            if (messageObject.messageOwner.media.title != null && messageObject.messageOwner.media.title.length() > 0) {
                actionBar.setTitle(messageObject.messageOwner.media.title);
                if (messageObject.messageOwner.media.address != null && messageObject.messageOwner.media.address.length() > 0) {
                    actionBar.setSubtitle(messageObject.messageOwner.media.address);
                }
            } else {
                actionBar.setTitle(LocaleController.getString("ChatLocation", R.string.ChatLocation));
            }
            menu.addItem(share, R.drawable.share);
        } else {
            actionBar.setTitle(LocaleController.getString("ShareLocation", R.string.ShareLocation));
        }
        /* TFOSS: TileSources https://github.com/osmdroid/osmdroid/wiki/Map-Sources
        ActionBarMenuItem item = menu.addItem(0, R.drawable.ic_ab_other);
        item.addSubItem(map_list_menu_map, LocaleController.getString("Map", R.string.Map));
        item.addSubItem(map_list_menu_satellite, LocaleController.getString("Satellite", R.string.Satellite));
        item.addSubItem(map_list_menu_hybrid, LocaleController.getString("Hybrid", R.string.Hybrid));
        */
        fragmentView = new FrameLayout(context) {
            private boolean first = true;

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);

                if (changed) {
                    fixLayoutInternal(first);
                    first = false;
                }
            }
        };
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        locationButton = new ImageView(context);
        Drawable drawable = Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56), Theme.getColor(Theme.key_profile_actionBackground), Theme.getColor(Theme.key_profile_actionPressedBackground));
        if (Build.VERSION.SDK_INT < 21) {
            Drawable shadowDrawable = context.getResources().getDrawable(R.drawable.floating_shadow_profile).mutate();
            shadowDrawable.setColorFilter(new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.MULTIPLY));
            CombinedDrawable combinedDrawable = new CombinedDrawable(shadowDrawable, drawable, 0, 0);
            combinedDrawable.setIconSize(AndroidUtilities.dp(56), AndroidUtilities.dp(56));
            drawable = combinedDrawable;
        }
        locationButton.setBackgroundDrawable(drawable);
        locationButton.setImageResource(R.drawable.myloc_on);
        locationButton.setScaleType(ImageView.ScaleType.CENTER);
        locationButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_profile_actionIcon), PorterDuff.Mode.MULTIPLY));
        if (Build.VERSION.SDK_INT >= 21) {
            StateListAnimator animator = new StateListAnimator();
            animator.addState(new int[]{android.R.attr.state_pressed}, ObjectAnimator.ofFloat(locationButton, "translationZ", AndroidUtilities.dp(2), AndroidUtilities.dp(4)).setDuration(200));
            animator.addState(new int[]{}, ObjectAnimator.ofFloat(locationButton, "translationZ", AndroidUtilities.dp(4), AndroidUtilities.dp(2)).setDuration(200));
            locationButton.setStateListAnimator(animator);
            locationButton.setOutlineProvider(new ViewOutlineProvider() {
                @SuppressLint("NewApi")
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, AndroidUtilities.dp(56), AndroidUtilities.dp(56));
                }
            });
        }

        if (messageObject != null) {
            mapView = new MapView(context);
            frameLayout.setBackgroundDrawable(new MapPlaceholderDrawable());

            bottomView = new FrameLayout(context);
            Drawable background = context.getResources().getDrawable(R.drawable.location_panel);
            background.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhite), PorterDuff.Mode.MULTIPLY));
            bottomView.setBackgroundDrawable(background);
            frameLayout.addView(bottomView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 60, Gravity.LEFT | Gravity.BOTTOM));
            bottomView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (userLocation != null) {
                        final IMapController controller = mapView.getController();
                        controller.setZoom(mapView.getMaxZoomLevel() - 1);
                        controller.animateTo(new GeoPoint(userLocation.getLatitude(), userLocation.getLongitude()));
                    }
                }
            });

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(AndroidUtilities.dp(20));
            bottomView.addView(avatarImageView, LayoutHelper.createFrame(40, 40, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 0 : 12, 12, LocaleController.isRTL ? 12 : 0, 0));

            nameTextView = new TextView(context);
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTextView.setMaxLines(1);
            nameTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            nameTextView.setSingleLine(true);
            nameTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            bottomView.addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 12 : 72, 10, LocaleController.isRTL ? 72 : 12, 0));

            distanceTextView = new TextView(context);
            distanceTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            distanceTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            distanceTextView.setMaxLines(1);
            distanceTextView.setEllipsize(TextUtils.TruncateAt.END);
            distanceTextView.setSingleLine(true);
            distanceTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            bottomView.addView(distanceTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 12 : 72, 33, LocaleController.isRTL ? 72 : 12, 0));

            userLocation = new Location("network");
            userLocation.setLatitude(messageObject.messageOwner.media.geo.lat);
            userLocation.setLongitude(messageObject.messageOwner.media.geo._long);

            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    if (mapView != null && getParentActivity() != null) {
                        mapView.setPadding(0, 0, 0, AndroidUtilities.dp(10));
                        onMapInit();
                        mapsInitialized = true;
                    }
                }
            });

            attributionOverlay = new TextView(context);
            attributionOverlay.setText(Html.fromHtml("© <a href=\"http://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"));
            attributionOverlay.setShadowLayer(1,-1,-1, Color.WHITE);
            attributionOverlay.setLinksClickable(true);
            attributionOverlay.setMovementMethod(LinkMovementMethod.getInstance());
            frameLayout.addView(attributionOverlay, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.BOTTOM, LocaleController.isRTL ? 0 : 4, 0, LocaleController.isRTL ? 4 : 0, 60));

            routeButton = new ImageView(context);
            drawable = Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56), Theme.getColor(Theme.key_chats_actionBackground), Theme.getColor(Theme.key_chats_actionPressedBackground));
            if (Build.VERSION.SDK_INT < 21) {
                Drawable shadowDrawable = context.getResources().getDrawable(R.drawable.floating_shadow).mutate();
                shadowDrawable.setColorFilter(new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.MULTIPLY));
                CombinedDrawable combinedDrawable = new CombinedDrawable(shadowDrawable, drawable, 0, 0);
                combinedDrawable.setIconSize(AndroidUtilities.dp(56), AndroidUtilities.dp(56));
                drawable = combinedDrawable;
            }
            routeButton.setBackgroundDrawable(drawable);
            routeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY));
            routeButton.setImageResource(R.drawable.navigate);
            routeButton.setScaleType(ImageView.ScaleType.CENTER);
            if (Build.VERSION.SDK_INT >= 21) {
                StateListAnimator animator = new StateListAnimator();
                animator.addState(new int[]{android.R.attr.state_pressed}, ObjectAnimator.ofFloat(routeButton, "translationZ", AndroidUtilities.dp(2), AndroidUtilities.dp(4)).setDuration(200));
                animator.addState(new int[]{}, ObjectAnimator.ofFloat(routeButton, "translationZ", AndroidUtilities.dp(4), AndroidUtilities.dp(2)).setDuration(200));
                routeButton.setStateListAnimator(animator);
                routeButton.setOutlineProvider(new ViewOutlineProvider() {
                    @SuppressLint("NewApi")
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, AndroidUtilities.dp(56), AndroidUtilities.dp(56));
                    }
                });
            }
            frameLayout.addView(routeButton, LayoutHelper.createFrame(Build.VERSION.SDK_INT >= 21 ? 56 : 60, Build.VERSION.SDK_INT >= 21 ? 56 : 60, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, LocaleController.isRTL ? 14 : 0, 0, LocaleController.isRTL ? 0 : 14, 28));
            routeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 23) {
                        Activity activity = getParentActivity();
                        if (activity != null) {
                            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                showPermissionAlert(true);
                                return;
                            }
                        }
                    }
                    if (myLocation != null) {
                        try {
                            /* TFOSS: todo: replace with geo intent? */
                            Intent intent = new Intent(android.content.Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "http://maps.google.com/maps?saddr=%f,%f&daddr=%f,%f", myLocation.getLatitude(), myLocation.getLongitude(), messageObject.messageOwner.media.geo.lat, messageObject.messageOwner.media.geo._long)));
                            getParentActivity().startActivity(intent);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                }
            });

            frameLayout.addView(locationButton, LayoutHelper.createFrame(Build.VERSION.SDK_INT >= 21 ? 56 : 60, Build.VERSION.SDK_INT >= 21 ? 56 : 60, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, LocaleController.isRTL ? 14 : 0, 0, LocaleController.isRTL ? 0 : 14, 100));
            locationButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 23) {
                        Activity activity = getParentActivity();
                        if (activity != null) {
                            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                showPermissionAlert(true);
                                return;
                            }
                        }
                    }
                    if (myLocation != null && mapView != null) {
                        final IMapController controller = mapView.getController();
                        controller.setZoom(mapView.getMaxZoomLevel() - 1);
                        controller.animateTo(new GeoPoint(myLocation.getLatitude(), myLocation.getLongitude()));
                    }
                }
            });
        } else {
            mapViewClip = new FrameLayout(context);
            mapViewClip.setBackgroundDrawable(new MapPlaceholderDrawable());
            if (adapter != null) {
                adapter.destroy();
            }

            listView = new RecyclerListView(context);
            listView.setAdapter(adapter = new LocationActivityAdapter(context));
            listView.setVerticalScrollBarEnabled(false);
            listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));

            listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (adapter.getItemCount() == 0) {
                        return;
                    }
                    int position = layoutManager.findFirstVisibleItemPosition();
                    if (position == RecyclerView.NO_POSITION) {
                        return;
                    }
                    updateClipView(position);
                }
            });
            listView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
                @Override
                public void onItemClick(View view, int position) {
                    if (position == 1) {
                        if (delegate != null && userLocation != null) {
                            TLRPC.TL_messageMediaGeo location = new TLRPC.TL_messageMediaGeo();
                            location.geo = new TLRPC.TL_geoPoint();
                            location.geo.lat = userLocation.getLatitude();
                            location.geo._long = userLocation.getLongitude();
                            delegate.didSelectLocation(location);
                        }
                        finishFragment();
                    } else {
                        TLRPC.TL_messageMediaVenue object = adapter.getItem(position);
                        if (object != null && delegate != null) {
                            delegate.didSelectLocation(object);
                        }
                        finishFragment();
                    }
                }
            });
            adapter.setOverScrollHeight(overScrollHeight);

            frameLayout.addView(mapViewClip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));

            mapView = new MapView(context) {
                @Override
                public boolean onTouchEvent(MotionEvent ev) {

                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        if (animatorSet != null) {
                            animatorSet.cancel();
                        }
                        animatorSet = new AnimatorSet();
                        animatorSet.setDuration(200);
                        animatorSet.playTogether(
                                ObjectAnimator.ofFloat(markerImageView, "translationY", markerTop + -AndroidUtilities.dp(10)),
                                ObjectAnimator.ofFloat(markerXImageView, "alpha", 1.0f));
                        animatorSet.start();
                    } else if (ev.getAction() == MotionEvent.ACTION_UP) {
                        if (animatorSet != null) {
                            animatorSet.cancel();
                        }
                        animatorSet = new AnimatorSet();
                        animatorSet.setDuration(200);
                        animatorSet.playTogether(
                                ObjectAnimator.ofFloat(markerImageView, "translationY", markerTop),
                                ObjectAnimator.ofFloat(markerXImageView, "alpha", 0.0f));
                        animatorSet.start();
                    }
                    if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                        if (!userLocationMoved) {
                            AnimatorSet animatorSet = new AnimatorSet();
                            animatorSet.setDuration(200);
                            animatorSet.play(ObjectAnimator.ofFloat(locationButton, "alpha", 1.0f));
                            animatorSet.start();
                            userLocationMoved = true;
                        }
                        if (mapView != null && userLocation != null) {
                            userLocation.setLatitude(mapView.getMapCenter().getLatitude());
                            userLocation.setLongitude(mapView.getMapCenter().getLongitude());
                        }
                        adapter.setCustomLocation(userLocation);
                    }
                    return super.onTouchEvent(ev);
                }
            };

            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    if (mapView != null && getParentActivity() != null) {
                        mapView.setPadding(0, 0, 0, AndroidUtilities.dp(10));
                        onMapInit();
                        mapsInitialized = true;
                    }
                }
            });

            View shadow = new View(context);
            shadow.setBackgroundResource(R.drawable.header_shadow_reverse);
            mapViewClip.addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3, Gravity.LEFT | Gravity.BOTTOM));

            markerImageView = new ImageView(context);
            markerImageView.setImageResource(R.drawable.map_pin);
            mapViewClip.addView(markerImageView, LayoutHelper.createFrame(24, 42, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            markerXImageView = new ImageView(context);
            markerXImageView.setAlpha(0.0f);
            markerXImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_location_markerX), PorterDuff.Mode.MULTIPLY));
            markerXImageView.setImageResource(R.drawable.place_x);
            mapViewClip.addView(markerXImageView, LayoutHelper.createFrame(14, 14, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            attributionOverlay = new TextView(context);
            attributionOverlay.setText(Html.fromHtml("© <a href=\"http://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"));
            attributionOverlay.setShadowLayer(1,-1,-1, Color.WHITE);
            attributionOverlay.setLinksClickable(true);
            attributionOverlay.setMovementMethod(LinkMovementMethod.getInstance());
            mapViewClip.addView(attributionOverlay, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.BOTTOM, LocaleController.isRTL ? 0 : 4, 0, LocaleController.isRTL ? 4 : 0, 4));

            mapViewClip.addView(locationButton, LayoutHelper.createFrame(Build.VERSION.SDK_INT >= 21 ? 56 : 60, Build.VERSION.SDK_INT >= 21 ? 56 : 60, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, LocaleController.isRTL ? 14 : 0, 0, LocaleController.isRTL ? 0 : 14, 14));
            locationButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 23) {
                        Activity activity = getParentActivity();
                        if (activity != null) {
                            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                showPermissionAlert(false);
                                return;
                            }
                        }
                    }
                    if (myLocation != null && mapView != null) {
                        AnimatorSet animatorSet = new AnimatorSet();
                        animatorSet.setDuration(200);
                        animatorSet.play(ObjectAnimator.ofFloat(locationButton, "alpha", 0.0f));
                        animatorSet.start();
                        adapter.setCustomLocation(null);
                        userLocationMoved = false;

                        final IMapController controller = mapView.getController();
                        controller.setZoom(mapView.getMaxZoomLevel() - 1);
                        controller.animateTo(new GeoPoint(myLocation.getLatitude(), myLocation.getLongitude()));
                    }
                }
            });
            locationButton.setAlpha(0.0f);
            frameLayout.addView(actionBar);
        }

        return fragmentView;
    }

    private void onMapInit() {
        if (mapView == null) {
            return;
        }

        final IMapController controller = mapView.getController();
        controller.setZoom(mapView.getMaxZoomLevel() - 1);

        if (messageObject != null) {
            GeoPoint latLng = new GeoPoint(userLocation.getLatitude(), userLocation.getLongitude());
            Marker marker = new Marker(mapView);
            marker.setPosition(latLng);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            if (Build.VERSION.SDK_INT >= 21) {
                marker.setIcon(getParentActivity().getDrawable(R.drawable.map_pin));
            } else {
                marker.setIcon(getParentActivity().getResources().getDrawable(R.drawable.map_pin));
            }
            mapView.getOverlays().add(marker);
            controller.animateTo(latLng);
        } else {
            userLocation = new Location("network");
            userLocation.setLatitude(20.659322);
            userLocation.setLongitude(-11.406250);
        }

        mapView.setMultiTouchControls(true);

        GpsMyLocationProvider imlp = new GpsMyLocationProvider(getParentActivity());
        imlp.setLocationUpdateMinDistance(10);
        imlp.setLocationUpdateMinTime(10000);
        imlp.addLocationSource(LocationManager.NETWORK_PROVIDER);
        myLocationOverlay = new MyLocationNewOverlay(imlp, mapView) {
            @Override
            public void onLocationChanged(final Location location, IMyLocationProvider source) {
                super.onLocationChanged(location, source);
                if (location != null) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            positionMarker(location);
                        }
                    });
                }
            }
        };
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.setDrawAccuracyEnabled(true);

        myLocationOverlay.runOnFirstFix(new Runnable() {
            public void run() {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        positionMarker(myLocationOverlay.getLastFix());
                    }
                });
            }
        });
        mapView.getOverlays().add(myLocationOverlay);
        positionMarker(myLocation = getLastLocation());

        attributionOverlay.bringToFront();
    }

    private void showPermissionAlert(boolean byButton) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
        if (byButton) {
            builder.setMessage(LocaleController.getString("PermissionNoLocationPosition", R.string.PermissionNoLocationPosition));
        } else {
            builder.setMessage(LocaleController.getString("PermissionNoLocation", R.string.PermissionNoLocation));
        }
        builder.setNegativeButton(LocaleController.getString("PermissionOpenSettings", R.string.PermissionOpenSettings), new DialogInterface.OnClickListener() {
            @TargetApi(Build.VERSION_CODES.GINGERBREAD)
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (getParentActivity() == null) {
                    return;
                }
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
                    getParentActivity().startActivity(intent);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        });
        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), null);
        showDialog(builder.create());
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen) {
            try {
                if (mapView.getParent() instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) mapView.getParent();
                    viewGroup.removeView(mapView);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (mapViewClip != null) {
                mapViewClip.addView(mapView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, overScrollHeight + AndroidUtilities.dp(10), Gravity.TOP | Gravity.LEFT));
                updateClipView(layoutManager.findFirstVisibleItemPosition());
            } else if (fragmentView != null) {
                ((FrameLayout) fragmentView).addView(mapView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
            }
        }
    }

    private void updateClipView(int firstVisibleItem) {
        if (firstVisibleItem == RecyclerView.NO_POSITION) {
            return;
        }
        int height = 0;
        int top = 0;
        View child = listView.getChildAt(0);
        if (child != null) {
            if (firstVisibleItem == 0) {
                top = child.getTop();
                height = overScrollHeight + (top < 0 ? top : 0);
            }
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mapViewClip.getLayoutParams();
            if (layoutParams != null) {
                mapViewClip.setTranslationY(Math.min(0, top));
                mapView.setTranslationY(Math.max(0, -top / 2));
                markerImageView.setTranslationY(markerTop = -top - AndroidUtilities.dp(42) + height / 2);
                markerXImageView.setTranslationY(-top - AndroidUtilities.dp(7) + height / 2);
                layoutParams = (FrameLayout.LayoutParams) mapView.getLayoutParams();
                if (layoutParams != null && layoutParams.height != overScrollHeight + AndroidUtilities.dp(10)) {
                    layoutParams.height = overScrollHeight + AndroidUtilities.dp(10);
                    if (mapView != null) {
                        mapView.setPadding(0, 0, 0, AndroidUtilities.dp(10));
                    }
                    mapView.setLayoutParams(layoutParams);
                }
            }
        }
    }

    private void fixLayoutInternal(final boolean resume) {
        if (listView != null) {
            int height = (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight();
            int viewHeight = fragmentView.getMeasuredHeight();
            if (viewHeight == 0) {
                return;
            }
            overScrollHeight = viewHeight - AndroidUtilities.dp(66) - height;

            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
            layoutParams.topMargin = height;
            listView.setLayoutParams(layoutParams);
            layoutParams = (FrameLayout.LayoutParams) mapViewClip.getLayoutParams();
            layoutParams.topMargin = height;
            layoutParams.height = overScrollHeight;
            mapViewClip.setLayoutParams(layoutParams);

            adapter.setOverScrollHeight(overScrollHeight);
            layoutParams = (FrameLayout.LayoutParams) mapView.getLayoutParams();
            if (layoutParams != null) {
                layoutParams.height = overScrollHeight + AndroidUtilities.dp(10);
                if (mapView != null) {
                    mapView.setPadding(0, 0, 0, AndroidUtilities.dp(10));
                }
                mapView.setLayoutParams(layoutParams);
            }
            adapter.notifyDataSetChanged();

            if (resume) {
                layoutManager.scrollToPositionWithOffset(0, -(int) (AndroidUtilities.dp(56) * 2.5f + AndroidUtilities.dp(36 + 66)));
                updateClipView(layoutManager.findFirstVisibleItemPosition());
                listView.post(new Runnable() {
                    @Override
                    public void run() {
                        layoutManager.scrollToPositionWithOffset(0, -(int) (AndroidUtilities.dp(56) * 2.5f + AndroidUtilities.dp(36 + 66)));
                        updateClipView(layoutManager.findFirstVisibleItemPosition());
                    }
                });
            } else {
                updateClipView(layoutManager.findFirstVisibleItemPosition());
            }
        }
    }

    private Location getLastLocation() {

        if (Build.VERSION.SDK_INT >= 23 && getParentActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && getParentActivity().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Location l = new Location("network");
            l.setLatitude(20.659322);
            l.setLongitude(-11.406250);
            return l;
        } else {
            LocationManager lm = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
            List<String> providers = lm.getProviders(true);
            Location l = null;
            for (int i = providers.size() - 1; i >= 0; i--) {
                l = lm.getLastKnownLocation(providers.get(i));
                if (l != null) {
                    break;
                }
            }
            return l;
        }
    }

    private void updateUserData() {
        if (messageObject != null && avatarImageView != null) {
            int fromId = messageObject.messageOwner.from_id;
            if (messageObject.isForwarded()) {
                if (messageObject.messageOwner.fwd_from.channel_id != 0) {
                    fromId = -messageObject.messageOwner.fwd_from.channel_id;
                } else {
                    fromId = messageObject.messageOwner.fwd_from.from_id;
                }
            }
            String name = "";
            TLRPC.FileLocation photo = null;
            avatarDrawable = null;
            if (fromId > 0) {
                TLRPC.User user = MessagesController.getInstance().getUser(fromId);
                if (user != null) {
                    if (user.photo != null) {
                        photo = user.photo.photo_small;
                    }
                    avatarDrawable = new AvatarDrawable(user);
                    name = UserObject.getUserName(user);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance().getChat(-fromId);
                if (chat != null) {
                    if (chat.photo != null) {
                        photo = chat.photo.photo_small;
                    }
                    avatarDrawable = new AvatarDrawable(chat);
                    name = chat.title;
                }
            }
            if (avatarDrawable != null) {
                avatarImageView.setImage(photo, null, avatarDrawable);
                nameTextView.setText(name);
            } else {
                avatarImageView.setImageDrawable(null);
            }
        }
    }

    private void positionMarker(Location location) {
        if (location == null) {
            return;
        }
        myLocation = new Location(location);
        if (messageObject != null) {
            if (userLocation != null && distanceTextView != null) {
                float distance = location.distanceTo(userLocation);
                if (distance < 1000) {
                    distanceTextView.setText(String.format("%d %s", (int) (distance), LocaleController.getString("MetersAway", R.string.MetersAway)));
                } else {
                    distanceTextView.setText(String.format("%.2f %s", distance / 1000.0f, LocaleController.getString("KMetersAway", R.string.KMetersAway)));
                }
            }
        } else if (mapView != null) {
            GeoPoint latLng = new GeoPoint(location.getLatitude(), location.getLongitude());
            if (adapter != null) {
                adapter.setGpsLocation(myLocation);
            }
            if (!userLocationMoved) {
                userLocation = new Location(location);
                if (firstWas) {
                    final IMapController controller = mapView.getController();
                    controller.animateTo(latLng);
                } else {
                    firstWas = true;
                    final IMapController controller = mapView.getController();
                    controller.setZoom(mapView.getMaxZoomLevel() - 1);
                    controller.animateTo(latLng);
                }
            }
        }
    }

    public void setMessageObject(MessageObject message) {
        messageObject = message;
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            if ((mask & MessagesController.UPDATE_MASK_AVATAR) != 0 || (mask & MessagesController.UPDATE_MASK_NAME) != 0) {
                updateUserData();
            }
        } else if (id == NotificationCenter.closeChats) {
            removeSelfFromStack();
        } else if (id == NotificationCenter.locationPermissionGranted) {
            if (mapView != null && mapsInitialized) {
                myLocationOverlay.enableMyLocation();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mapView != null && mapsInitialized) {
            myLocationOverlay.disableMyLocation();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);

        if (mapView != null && mapsInitialized) {
            myLocationOverlay.enableMyLocation();
        }
        updateUserData();
        fixLayoutInternal(true);
        if (checkPermission && Build.VERSION.SDK_INT >= 23) {
            Activity activity = getParentActivity();
            if (activity != null) {
                checkPermission = false;
                if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 2);
                }
            }
        }
    }

    public void setDelegate(LocationActivityDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public ThemeDescription[] getThemeDescriptions() {
        ThemeDescription.ThemeDescriptionDelegate сellDelegate = new ThemeDescription.ThemeDescriptionDelegate() {
            @Override
            public void didSetColor(int color) {
                updateUserData();
            }
        };
        return new ThemeDescription[]{
                new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite),

                new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault),
                new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBMENUBACKGROUND, null, null, null, null, Theme.key_actionBarDefaultSubmenuBackground),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBMENUITEM, null, null, null, null, Theme.key_actionBarDefaultSubmenuItem),

                new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector),

                new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider),

                new ThemeDescription(locationButton, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_profile_actionIcon),
                new ThemeDescription(locationButton, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_profile_actionBackground),
                new ThemeDescription(locationButton, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null, null, Theme.key_profile_actionPressedBackground),

                new ThemeDescription(bottomView, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_windowBackgroundWhite),

                new ThemeDescription(nameTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText),

                new ThemeDescription(distanceTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteValueText),

                new ThemeDescription(routeButton, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_chats_actionIcon),
                new ThemeDescription(routeButton, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_chats_actionBackground),
                new ThemeDescription(routeButton, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null, null, Theme.key_chats_actionPressedBackground),

                new ThemeDescription(markerXImageView, 0, null, null, null, null, Theme.key_location_markerX),

                new ThemeDescription(null, 0, null, null, new Drawable[]{Theme.avatar_photoDrawable, Theme.avatar_broadcastDrawable}, сellDelegate, Theme.key_avatar_text),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundRed),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundOrange),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundViolet),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundGreen),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundCyan),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundBlue),
                new ThemeDescription(null, 0, null, null, null, сellDelegate, Theme.key_avatar_backgroundPink),

                new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{SendLocationCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_location_sendLocationIcon),
                new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, new Class[]{SendLocationCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_location_sendLocationBackground),
                new ThemeDescription(listView, 0, new Class[]{SendLocationCell.class}, new String[]{"titleTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText7),
                new ThemeDescription(listView, 0, new Class[]{SendLocationCell.class}, new String[]{"accurateTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3),

                new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{LocationCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3),
                new ThemeDescription(listView, 0, new Class[]{LocationCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText),
                new ThemeDescription(listView, 0, new Class[]{LocationCell.class}, new String[]{"addressTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3),
        };
    }
}