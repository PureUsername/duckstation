package com.github.stenzek.duckstation;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_EXTERNAL_STORAGE_PERMISSIONS = 1;
    private static final int REQUEST_ADD_DIRECTORY_TO_GAME_LIST = 2;
    private static final int REQUEST_IMPORT_BIOS_IMAGE = 3;
    private static final int REQUEST_START_FILE = 4;
    private static final int REQUEST_SETTINGS = 5;
    private static final int REQUEST_EDIT_GAME_DIRECTORIES = 6;

    private GameList mGameList;
    private ListView mGameListView;
    private GameListFragment mGameListFragment;
    private GameGridFragment mGameGridFragment;
    private boolean mHasExternalStoragePermissions = false;
    private boolean mIsShowingGameGrid = false;

    private static String getTitleString() {
        String scmVersion = AndroidHostInterface.getScmVersion();
        final int gitHashPos = scmVersion.indexOf("-g");
        if (gitHashPos > 0)
            scmVersion = scmVersion.substring(0, gitHashPos);

        return String.format("DuckStation %s", scmVersion);
    }

    public GameList getGameList() {
        return mGameList;
    }

    public boolean shouldResumeStateByDefault() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getBoolean("Main/SaveStateOnExit", true);
    }

    private void setLanguage() {
        String language = PreferenceManager.getDefaultSharedPreferences(this).getString("Main/Language", "none");
        if (language == null || language.equals("none")) {
            return;
        }

        String[] parts = language.split("-");
        if (parts.length < 2)
            return;

        Locale locale = new Locale(parts[0], parts[1]);
        Locale.setDefault(locale);

        Resources res = getResources();
        Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    private void setTheme() {
        String theme = PreferenceManager.getDefaultSharedPreferences(this).getString("Main/Theme", "follow_system");
        if (theme == null)
            return;

        if (theme.equals("follow_system")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        } else if (theme.equals("light")) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (theme.equals("dark")) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }

    private void loadSettings() {
        setLanguage();
        setTheme();

        mIsShowingGameGrid = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("Main/GameGridView", false);
    }

    private void switchGameListView() {
        mIsShowingGameGrid = !mIsShowingGameGrid;
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean("Main/GameGridView", mIsShowingGameGrid)
                .commit();

        updateGameListFragment();
        invalidateOptionsMenu();
    }

    private void updateGameListFragment() {
        getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true).
                replace(R.id.content_fragment, mIsShowingGameGrid ? mGameGridFragment : mGameListFragment)
                .commit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSettings();

        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle(getTitleString());

        findViewById(R.id.fab_add_game_directory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startAddGameDirectory();
            }
        });
        findViewById(R.id.fab_resume).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startEmulation(null, shouldResumeStateByDefault());
            }
        });

        // Set up game list view.
        mGameList = new GameList(this);
        mGameListFragment = new GameListFragment(this);
        mGameGridFragment = new GameGridFragment(this);
        updateGameListFragment();

        mHasExternalStoragePermissions = checkForExternalStoragePermissions();
        if (mHasExternalStoragePermissions)
            completeStartup();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.remove("android:support:fragments");
    }

    private void completeStartup() {
        if (!AndroidHostInterface.hasInstance() && !AndroidHostInterface.createInstance(this)) {
            Log.i("MainActivity", "Failed to create host interface");
            throw new RuntimeException("Failed to create host interface");
        }

        AndroidHostInterface.getInstance().setContext(this);
        mGameList.refresh(false, false, this);
    }

    private void startAddGameDirectory() {
        if (!checkForExternalStoragePermissions())
            return;

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        startActivityForResult(Intent.createChooser(i, getString(R.string.main_activity_choose_directory)),
                REQUEST_ADD_DIRECTORY_TO_GAME_LIST);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        final MenuItem switchViewItem = menu.findItem(R.id.action_switch_view);
        if (switchViewItem != null) {
            switchViewItem.setTitle(mIsShowingGameGrid ? R.string.action_show_game_list : R.string.action_show_game_grid);
            switchViewItem.setIcon(mIsShowingGameGrid ? R.drawable.ic_baseline_view_list_24 : R.drawable.ic_baseline_grid_view_24);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_start_bios) {
            startEmulation(null, false);
        } else if (id == R.id.action_start_file) {
            startStartFile();
        } else if (id == R.id.action_edit_game_directories) {
            Intent intent = new Intent(this, GameDirectoriesActivity.class);
            startActivityForResult(intent, REQUEST_EDIT_GAME_DIRECTORIES);
            return true;
        } else if (id == R.id.action_scan_for_new_games) {
            mGameList.refresh(false, false, this);
        } else if (id == R.id.action_rescan_all_games) {
            mGameList.refresh(true, true, this);
        } else if (id == R.id.action_import_bios) {
            importBIOSImage();
        } else if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_SETTINGS);
            return true;
        } else if (id == R.id.action_controller_mapping) {
            Intent intent = new Intent(this, ControllerMappingActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_switch_view) {
            switchGameListView();
            return true;
        } else if (id == R.id.action_show_version) {
            showVersion();
            return true;
        } else if (id == R.id.action_github_respository) {
            openGithubRepository();
            return true;
        } else if (id == R.id.action_discord_server) {
            openDiscordServer();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ADD_DIRECTORY_TO_GAME_LIST: {
                if (resultCode != RESULT_OK)
                    return;

                String path = GameDirectoriesActivity.getPathFromTreeUri(this, data.getData());
                if (path == null)
                    return;

                GameDirectoriesActivity.addSearchDirectory(this, path, true);
                mGameList.refresh(false, false, this);
            }
            break;

            case REQUEST_IMPORT_BIOS_IMAGE: {
                if (resultCode != RESULT_OK)
                    return;

                onImportBIOSImageResult(data.getData());
            }
            break;

            case REQUEST_START_FILE: {
                if (resultCode != RESULT_OK)
                    return;

                String path = GameDirectoriesActivity.getPathFromUri(this, data.getData());
                if (path == null)
                    return;

                startEmulation(path, shouldResumeStateByDefault());
            }
            break;

            case REQUEST_SETTINGS: {
                loadSettings();
            }
            break;

            case REQUEST_EDIT_GAME_DIRECTORIES: {
                mGameList.refresh(false, false, this);
            }
            break;
        }
    }

    private boolean checkForExternalStoragePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat
                        .checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_EXTERNAL_STORAGE_PERMISSIONS);
        return false;
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        // check that all were successful
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                if (!mHasExternalStoragePermissions) {
                    mHasExternalStoragePermissions = true;
                    completeStartup();
                }
            } else {
                Toast.makeText(this,
                        R.string.main_activity_external_storage_permissions_error,
                        Toast.LENGTH_LONG);
                finish();
            }
        }
    }

    public boolean openGameProperties(String path) {
        Intent intent = new Intent(this, GamePropertiesActivity.class);
        intent.putExtra("path", path);
        startActivity(intent);
        return true;
    }

    public boolean startEmulation(String bootPath, boolean resumeState) {
        if (!doBIOSCheck())
            return false;

        Intent intent = new Intent(this, EmulationActivity.class);
        intent.putExtra("bootPath", bootPath);
        intent.putExtra("resumeState", resumeState);
        startActivity(intent);
        return true;
    }

    private void startStartFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.main_activity_choose_disc_image)), REQUEST_START_FILE);
    }

    private boolean doBIOSCheck() {
        if (AndroidHostInterface.getInstance().hasAnyBIOSImages())
            return true;

        new AlertDialog.Builder(this)
                .setTitle(R.string.main_activity_missing_bios_image)
                .setMessage(R.string.main_activity_missing_bios_image_prompt)
                .setPositiveButton(R.string.main_activity_yes, (dialog, button) -> importBIOSImage())
                .setNegativeButton(R.string.main_activity_no, (dialog, button) -> {
                })
                .create()
                .show();

        return false;
    }

    private void importBIOSImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.main_activity_choose_bios_image)), REQUEST_IMPORT_BIOS_IMAGE);
    }

    private void onImportBIOSImageResult(Uri uri) {
        // This should really be 512K but just in case we wanted to support the other BIOSes in the future...
        final int MAX_BIOS_SIZE = 2 * 1024 * 1024;

        InputStream stream = null;
        try {
            stream = getContentResolver().openInputStream(uri);
        } catch (FileNotFoundException e) {
            Toast.makeText(this, R.string.main_activity_failed_to_open_bios_image, Toast.LENGTH_LONG);
            return;
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[512 * 1024];
            int len;
            while ((len = stream.read(buffer)) > 0) {
                os.write(buffer, 0, len);
                if (os.size() > MAX_BIOS_SIZE) {
                    throw new IOException(getString(R.string.main_activity_bios_image_too_large));
                }
            }
        } catch (IOException e) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.main_activity_failed_to_read_bios_image_prefix) + e.getMessage())
                    .setPositiveButton(R.string.main_activity_ok, (dialog, button) -> {
                    })
                    .create()
                    .show();
            return;
        }

        String importResult = AndroidHostInterface.getInstance().importBIOSImage(os.toByteArray());
        String message = (importResult == null) ? getString(R.string.main_activity_invalid_error) : ("BIOS '" + importResult + "' imported.");

        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(R.string.main_activity_ok, (dialog, button) -> {
                })
                .create()
                .show();
    }

    private void showVersion() {
        final String message = AndroidHostInterface.getFullScmVersion();
        new AlertDialog.Builder(this)
                .setTitle(R.string.main_activity_show_version_title)
                .setMessage(message)
                .setPositiveButton(R.string.main_activity_ok, (dialog, button) -> {
                })
                .setNeutralButton(R.string.main_activity_copy, (dialog, button) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null)
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.main_activity_show_version_title), message));
                })
                .create()
                .show();
    }

    private void openGithubRepository() {
        final String url = "https://github.com/stenzek/duckstation";
        final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(browserIntent);
    }

    private void openDiscordServer() {
        final String url = "https://discord.gg/Buktv3t";
        final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(browserIntent);
    }
}
