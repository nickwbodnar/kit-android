package com.lastinitial.stitch;

import android.app.Activity;
import android.app.LoaderManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;

public class MainActivity extends Activity implements
        LoaderManager.LoaderCallbacks<Cursor>{

    public static final String DETAILS_DB_ROWID = "com.lastinitial.stitch.DETAILS_DB_ROWID";

    public static final String HAS_SWIPED = "HAS_SWIPED";
    public static final String HAS_SHOWN_NUDGE_EDU_3 = "HAS_SHOWN_NUDGE_EDU_3";
    public static final String EDU_INFO_PREFS = "EDU_INFO_PREFS";

    public static final int FREQUENCY_DAILY = 0;
    public static final int FREQUENCY_WEEKLY = 1;
    public static final int FREQUENCY_MONTHLY = 2;
    public static final int FREQUENCY_YEARLY = 3;

    public static final int CONTACT_TYPE_SYSTEM = 1;
    public static final int CONTACT_TYPE_MANUAL = 2;
    public static final int CONTACT_TYPE_CALL = 3;
    public static final int CONTACT_TYPE_SMS = 4;

    // Loader for this component
    private static final int CONTACTS_LOADER = 0;

    private ContactsDbAdapter mDbHelper;
    private ResourceCursorAdapter mAdapter;
    private LastContactUpdater mUpdater = new LastContactUpdater();
    private ContactsCache mContactsCache;

    public static final int LOW_PRIORITY_TEXT_COLOR = Color.parseColor("#666666");
    public static final int LOW_PRIORITY_CLOCK_COLOR = Color.parseColor("#999999");
    public static final int ALARM_ICON_COLOR = Color.parseColor("#CCFF5050");

    // Default background color for lists in Holo.Light. Obtained by calling
    //    int[] attributes = { android.R.attr.colorBackground };
    //    TypedArray array = getTheme().obtainStyledAttributes(attributes);
    public static final int DEFAULT_BACKGROUND_COLOR = -789517;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AnalyticsUtil.logScreenImpression(this, "com.lastinitial.stitch.MainActivity");

        mDbHelper = new ContactsDbAdapter(this);
        mDbHelper.open();

        mUpdater = new LastContactUpdater();
//        mUpdater.update(this, mDbHelper); // This now happens in onStart

        mContactsCache = new ContactsCache(this);

        ComponentName receiver = new ComponentName(this, PeriodicUpdater.class);
        PackageManager pm = this.getPackageManager();

        int state = pm.getComponentEnabledSetting(receiver);

        if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            // We've been started up for the first time
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            // Manually start background service
            PeriodicUpdater periodicUpdater = new PeriodicUpdater();
            periodicUpdater.registerAlarmService(this);
        }

        // Now for the UI
        setContentView(R.layout.activity_main);

        View listBackground = findViewById(R.id.listBackground);

        TextView snoozeIcon = (TextView)listBackground.findViewById(R.id.snoozeIcon);
        snoozeIcon.setTypeface(FontUtils.getFontAwesome(this));
        TextView snoozeText = (TextView)listBackground.findViewById(R.id.snoozeText);
        long now = System.currentTimeMillis();
        snoozeText.setText(RelativeDateUtils.getRelativeTimeSpanString(
                now + SnoozeUtil.DEFAULT_SNOOZE_TIME, now));

        TextView talkedIcon = (TextView)listBackground.findViewById(R.id.talkedIcon);
        talkedIcon.setTypeface(FontUtils.getFontAwesome(this));

        ((TextView) findViewById(R.id.snoozeIconSwipeEdu)).setTypeface(FontUtils.getFontAwesome(this));
        ((TextView) findViewById(R.id.talkedIconSwipeEdu)).setTypeface(FontUtils.getFontAwesome(this));

        final ListView listView = (ListView) findViewById(R.id.entriesList);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                Uri contactUri = (Uri) view.getTag(R.id.view_lookup_uri);
                ContactsContract.QuickContact.showQuickContact(
                        adapterView.getContext(),
                        view, contactUri,
                        ContactsContract.QuickContact.MODE_LARGE,
                        null);
            }
        });

        /*
         * Initializes the CursorLoader. The URL_LOADER value is eventually passed
         * to onCreateLoader().
         */
        getLoaderManager().initLoader(CONTACTS_LOADER, null, this);

        mAdapter = new ResourceCursorAdapter(
                this,
                R.layout.item_last_contact,
                null, 0 ) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                ViewHolder viewHolder;
                if (view.getTag(R.id.view_holder) == null) {
                    viewHolder = new ViewHolder();
                    viewHolder.contactName = (TextView) view.findViewById(R.id.contactName);
                    viewHolder.nextDescription = (TextView) view.findViewById(R.id.next_description);
                    viewHolder.nextValue = (TextView) view.findViewById(R.id.next_value);
                    viewHolder.quickbadge = (ImageView) view.findViewById(R.id.quickbadge);
                    viewHolder.contactOptions = (ImageView) view.findViewById(R.id.contactOptions);
                    view.setTag(R.id.view_holder, viewHolder);

                    // Initialize some stuff here that's constant across all rows
                    viewHolder.nextDescription.setTypeface(FontUtils.getFontAwesome(context));
                    viewHolder.contactOptions.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Uri contactUri = (Uri) view.getTag(R.id.view_lookup_uri);
                            Long rowId = (Long) view.getTag(R.id.view_db_rowid);
                            Intent intent = new Intent(
                                    getApplicationContext(),
                                    EntryDetailsActivity.class);
                            intent.setData(contactUri);
                            intent.putExtra(DETAILS_DB_ROWID, rowId);
                            intent.setAction(Intent.ACTION_MAIN);
                            startActivity(intent);
                        }
                    });

                } else {
                    viewHolder = (ViewHolder) view.getTag(R.id.view_holder);
                }

                int lookupKeyIndex = cursor.getColumnIndex(ContactsDbAdapter.KEY_LOOKUP_KEY);
                String lookupKey = cursor.getString(lookupKeyIndex);

                viewHolder.contactName.setText(mContactsCache.getContactName(lookupKey));
                Uri thumbnail = mContactsCache.getContactImage(lookupKey);
                if (thumbnail != null) {
                    viewHolder.quickbadge.setImageURI(thumbnail);
                } else {
                    viewHolder.quickbadge.setImageResource(R.drawable.ic_action_person);
                }

                int nextContactIndex = cursor.getColumnIndex(ContactsDbAdapter.KEY_NEXT_CONTACT);
                long nextContact = cursor.getLong(nextContactIndex);

                CharSequence nextText = null;
                long currentTime = System.currentTimeMillis();
                if (nextContact > 0) {
                    nextText =
                            RelativeDateUtils.getRelativeTimeSpanString(nextContact, currentTime);
                } else {
                    nextText = getResources().getString(R.string.asap);
                }
                viewHolder.nextValue.setText(nextText);

                boolean isAlertingAlready =
                        (viewHolder.nextDescription.getCurrentTextColor() == ALARM_ICON_COLOR);
                // Check if the contact entry should be alerting and change the visual style
                // if needed.
                if (nextContact < System.currentTimeMillis()) {
                    if (!isAlertingAlready) {
                        viewHolder.nextDescription.setTextColor(ALARM_ICON_COLOR);
                        viewHolder.contactName.setTextColor(Color.BLACK);
                        view.setBackgroundColor(Color.WHITE);
                    }
                } else {
                    if (isAlertingAlready) {
                        viewHolder.nextDescription.setTextColor(LOW_PRIORITY_CLOCK_COLOR);
                        viewHolder.contactName.setTextColor(LOW_PRIORITY_TEXT_COLOR);
                    }
                    view.setBackgroundColor(DEFAULT_BACKGROUND_COLOR);
                }

                int rowIdIndex = cursor.getColumnIndex(ContactsDbAdapter.KEY_ROWID);
                long rowId = cursor.getLong(rowIdIndex);
                viewHolder.contactOptions.setTag(R.id.view_db_rowid, rowId);

                final Uri lookupUri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey);
                view.setTag(R.id.view_lookup_uri, lookupUri);
                viewHolder.contactOptions.setTag(R.id.view_lookup_uri, lookupUri);
            }
        };
        listView.setAdapter(mAdapter);

        SwipeDismissListViewTouchListener touchListener =
                new SwipeDismissListViewTouchListener(
                        listView,
                        listBackground,
                        new SwipeDismissListViewTouchListener.DismissCallbacks() {
                            @Override
                            public boolean canDismiss(int position) {
                                return true;
                            }

                            public void onDismiss(ListView listView,
                                                  int[] reverseSortedPositions,
                                                  boolean isDismissRight) {
                                for (int position : reverseSortedPositions) {
                                    long itemId = mAdapter.getItemId(position);
                                    if (isDismissRight) {
                                        SnoozeUtil snoozeUtil = new SnoozeUtil();
                                        snoozeUtil.snoozeContact(
                                                getApplicationContext(),
                                                itemId,
                                                SnoozeUtil.DEFAULT_SNOOZE_TIME);
                                    } else {
                                        mDbHelper.updateLastContacted(itemId,
                                                System.currentTimeMillis(),
                                                CONTACT_TYPE_MANUAL);
                                    }
                                }
                                // Todo(ak): This is gross. Because the ResourceCursorAdapter
                                // does all this gunk below (like swapping cursors)
                                // to load things in the background before
                                // rendering, .notifyDataSetChanged() gets called all the time
                                // and can't initiate a refresh of the list because that would
                                // cause an infinite loop.
                                // mAdapter.notifyDataSetChanged();
                                getLoaderManager().restartLoader(
                                        CONTACTS_LOADER, null, MainActivity.this);
                            }
                        });

        listView.setOnTouchListener(touchListener);
        listView.setOnScrollListener(touchListener.makeScrollListener());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Explicitly hide these in case they've been left visible
        findViewById(R.id.snoozeBackground).setVisibility(View.INVISIBLE);
        findViewById(R.id.talkedBackground).setVisibility(View.INVISIBLE);

        // If there are no contacts, show the nudge, and maybe the swipe EDU.
        View nudgeView = findViewById(R.id.nudge);
//        View swipeEduView = findViewById(R.id.swipeEDU);
        View nudgeCtaView = findViewById(R.id.nudgeCta);
        View nudgeContinuedView = findViewById(R.id.nudgeContinued);
        View nudgeEduCloserView = findViewById(R.id.nudgeEduCloser);

        long numContacts = mDbHelper.numContacts();
        if (numContacts == 0) {
//            swipeEduView.setVisibility(View.GONE);
            nudgeView.setVisibility(View.VISIBLE);
            nudgeEduCloserView.setVisibility(View.GONE);

            nudgeCtaView.setVisibility(View.VISIBLE);
            nudgeContinuedView.setVisibility(View.GONE);
        } else if (numContacts == 1) {
//            swipeEduView.setVisibility(View.GONE);
            nudgeView.setVisibility(View.VISIBLE);
            nudgeEduCloserView.setVisibility(View.GONE);

            nudgeCtaView.setVisibility(View.GONE);
            nudgeContinuedView.setVisibility(View.VISIBLE);
        }
        else {
            nudgeView.setVisibility(View.GONE);

            SharedPreferences prefs = getSharedPreferences(EDU_INFO_PREFS, 0 /* MODE_PRIVATE */);
            boolean hasShownNudgeEdu3 = prefs.getBoolean(HAS_SHOWN_NUDGE_EDU_3, false);
            if (hasShownNudgeEdu3) {
                boolean isRelease =
                        (0 == (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
                if (isRelease) {
                    nudgeEduCloserView.setVisibility(View.GONE);
                } else {
                    Log.v("MainActivity", "Showing Nudge EDU closer only because in DEBUG mode");
                    nudgeEduCloserView.setVisibility(View.VISIBLE);
                }
            } else {
                nudgeEduCloserView.setVisibility(View.VISIBLE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(HAS_SHOWN_NUDGE_EDU_3, true);
                editor.commit();
            }

//            SharedPreferences prefs = getSharedPreferences(EDU_INFO_PREFS, 0 /* MODE_PRIVATE */);
//            boolean hasSwiped = prefs.getBoolean(HAS_SWIPED, false);
//            if (hasSwiped) {
//                boolean isRelease =
//                        (0 == (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
//                if (isRelease) {
//                    swipeEduView.setVisibility(View.GONE);
//                } else {
//                    Log.v("MainActivity", "Showing Swipe EDU only because in DEBUG mode");
//                    swipeEduView.setVisibility(View.VISIBLE);
//                }
//            } else {
//                swipeEduView.setVisibility(View.VISIBLE);
//            }
        }

        mUpdater.update(this, mDbHelper);
        getLoaderManager().restartLoader(CONTACTS_LOADER, null, this);
    }


    @Override
    public Loader<Cursor> onCreateLoader(int loaderID, Bundle bundle)
    {
        /*
         * Takes action based on the ID of the Loader that's being created
         */
        switch (loaderID) {
            case CONTACTS_LOADER:
                // Returns a new CursorLoader
                return new SimpleCursorLoader(this) {
                    @Override
                    public Cursor loadInBackground() {
                        return mDbHelper.fetchAllContacts();
                    }
                };
            default:
                // An invalid id was passed in
                return null;
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        /*
         * Moves the query results into the adapter, causing the
         * ListView fronting this adapter to re-display
         */
        mAdapter.changeCursor(cursor);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        // This is called when the last Cursor provided to onLoadFinished()
        // above is about to be closed.  We need to make sure we are no
        // longer using it.
        mAdapter.swapCursor(null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        boolean isRelease = (0 == (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
        if (isRelease) {
            MenuItem notifications = menu.findItem(R.id.action_run_notifications);
            notifications.setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_run_notifications) {
            Intent intent = new Intent(this, PeriodicUpdater.class);
            sendBroadcast(intent);
        } else if (id == R.id.action_about) {
            Intent intent = new Intent(this, About.class);
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    public void addContact(View view) {
        addContact();
    }

    public void addContact() {
        Intent pickContactIntent = new Intent( Intent.ACTION_PICK, Contacts.CONTENT_FILTER_URI );
        pickContactIntent.setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
        startActivityForResult(pickContactIntent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                Uri contactUri = data.getData();
                Log.v(this.getClass().getCanonicalName(), "picker returned URI: " + contactUri);
                Intent intent = new Intent(this, EntryDetailsActivity.class);
                intent.setData(contactUri);
                intent.setAction(Intent.ACTION_EDIT);
                startActivity(intent);
            }
        }
    }

    static class ViewHolder {
        ImageView quickbadge;
        TextView contactName;
        TextView nextDescription;
        TextView nextValue;
        ImageView contactOptions;
    }
}