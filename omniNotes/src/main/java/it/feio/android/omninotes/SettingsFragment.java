/*
 * Copyright (C) 2013-2023 Federico Iosue (federico@iosue.it)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.feio.android.omninotes;

import static android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI;
import static android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
import static it.feio.android.omninotes.utils.ConstantsBase.DATABASE_NAME;
import static it.feio.android.omninotes.utils.ConstantsBase.DATE_FORMAT_EXPORT;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_AUTO_LOCATION;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_BACKUP_FOLDER_URI;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_COLORS_APP_DEFAULT;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_ENABLE_FILE_LOGGING;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_PASSWORD;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SHOW_UNCATEGORIZED;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SNOOZE_DEFAULT;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.reverse;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;
import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lazygeniouz.dfc.file.DocumentFileCompat;
import com.pixplicity.easyprefs.library.Prefs;
import it.feio.android.omninotes.async.DataBackupIntentService;
import it.feio.android.omninotes.exceptions.checked.ExternalStorageProviderException;
import it.feio.android.omninotes.helpers.AppVersionHelper;
import it.feio.android.omninotes.helpers.BackupHelper;
import it.feio.android.omninotes.helpers.ChangelogHelper;
import it.feio.android.omninotes.helpers.LanguageHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.PermissionsHelper;
import it.feio.android.omninotes.helpers.SpringImportHelper;
import it.feio.android.omninotes.helpers.notifications.NotificationsHelper;
import it.feio.android.omninotes.intro.IntroActivity;
import it.feio.android.omninotes.models.ONStyle;
import it.feio.android.omninotes.models.PasswordValidator.Result;
import it.feio.android.omninotes.utils.FileHelper;
import it.feio.android.omninotes.utils.IntentChecker;
import it.feio.android.omninotes.utils.PasswordHelper;
import it.feio.android.omninotes.utils.ResourcesUtils;
import it.feio.android.omninotes.utils.StorageHelper;
import it.feio.android.omninotes.utils.SystemHelper;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import rx.Observable;


public class SettingsFragment extends PreferenceFragmentCompat {

  private static final int SPRINGPAD_IMPORT = 0;
  private static final int RINGTONE_REQUEST_CODE = 100;
  private static final int ACCESS_DATA_FOR_EXPORT = 200;
  private static final int ACCESS_DATA_FOR_IMPORT = 210;
  public static final String XML_NAME = "xmlName";


  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    int xmlId = R.xml.settings;
    if (getArguments() != null && getArguments().containsKey(XML_NAME)) {
      xmlId = ResourcesUtils
          .getXmlId(OmniNotes.getAppContext(), ResourcesUtils.ResourceIdentifiers.XML, String
              .valueOf(getArguments().get(XML_NAME)));
    }
    addPreferencesFromResource(xmlId);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    // Nothing to do, init is performed into onResume
  }

  private void setTitle() {
    String title = getString(R.string.settings);
    if (getArguments() != null && getArguments().containsKey(XML_NAME)) {
      String xmlName = getArguments().getString(XML_NAME);
      if (!TextUtils.isEmpty(xmlName)) {
        int stringResourceId = getActivity().getResources()
            .getIdentifier(xmlName.replace("settings_",
                "settings_screen_"), "string", getActivity().getPackageName());
        title = stringResourceId != 0 ? getString(stringResourceId) : title;
      }
    }
    Toolbar toolbar = getActivity().findViewById(R.id.toolbar);
    if (toolbar != null) {
      toolbar.setTitle(title);
    }
  }


  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      getActivity().onBackPressed();
    } else {
      LogDelegate.e("Wrong element choosen: " + item.getItemId());
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onResume() {
    super.onResume();

    setTitle();

    // Export notes
    Preference export = findPreference("settings_export_data");
    if (export != null) {
      export.setSummary(BackupHelper.getBackupFolderPath());
      export.setOnPreferenceClickListener(arg0 -> {
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
          try {
            scopedStorageFolderChoosen();
            exportNotes();
          } catch (ExternalStorageProviderException e) {
            startIntentForScopedStorage(ACCESS_DATA_FOR_EXPORT);
          }
        } else {
          PermissionsHelper.requestPermission(getActivity(), permission.WRITE_EXTERNAL_STORAGE,
              R.string.permission_external_storage, getActivity().findViewById(R.id.crouton_handle),
              this::exportNotes);
        }
        return false;
      });
    }

    // Import notes
    Preference importData = findPreference("settings_import_data");
    if (importData != null) {
      if (StringUtils.isEmpty(Prefs.getString(PREF_PASSWORD, ""))) {
        importData.setSummary(getString(R.string.settings_import_summary));
      }
      importData.setOnPreferenceClickListener(arg0 -> {
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
          DocumentFileCompat backupFolder;
          try {
            backupFolder = scopedStorageFolderChoosen();
            importNotes(backupFolder);
          } catch (ExternalStorageProviderException e) {
            startIntentForScopedStorage(ACCESS_DATA_FOR_IMPORT);
          }
        } else {
          PermissionsHelper
              .requestPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE, R
                      .string.permission_external_storage,
                  getActivity().findViewById(R.id.crouton_handle), this::importNotes);
        }
        return false;
      });
    }

    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      Preference changeBackupFolder = findPreference("settings_change_backup_folder");
      if (changeBackupFolder != null) {
        changeBackupFolder.setVisible(true);
        changeBackupFolder.setOnPreferenceClickListener(arg0 -> {
          startIntentForScopedStorage(ACCESS_DATA_FOR_IMPORT);
          return false;
        });
      }
    }

//		// Autobackup feature integrity check
//		Preference backupIntegrityCheck = findPreference("settings_backup_integrity_check");
//		if (backupIntegrityCheck != null) {
//			backupIntegrityCheck.setOnPreferenceClickListener(arg0 -> {
//				List<LinkedList<DiffMatchPatch.Diff>> errors = BackupHelper.integrityCheck(StorageHelper
//						.getBackupDir(ConstantsBase.AUTO_BACKUP_DIR));
//				if (errors.isEmpty()) {
//					new MaterialDialog.Builder(activity)
//							.content("Everything is ok")
//							.positiveText(R.string.ok)
//							.build().show();
//				} else {
//					DiffMatchPatch diffMatchPatch = new DiffMatchPatch();
//					String content = Observable.from(errors).map(diffs -> diffMatchPatch.diffPrettyHtml(diffs) +
//							"<br/>").toList().toBlocking().first().toString();
//					View v = getActivity().getLayoutInflater().inflate(R.layout.webview, null);
//					((WebView) v.findViewById(R.ID.webview)).loadData(content, "text/html", null);
//					new MaterialDialog.Builder(activity)
//							.customView(v, true)
//							.positiveText(R.string.ok)
//							.negativeText("Copy to clipboard")
//							.onNegative((dialog, which) -> {
//								SystemHelper.copyToClipboard(activity, content);
//								Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show();
//							})
//							.build().show();
//				}
//				return false;
//			});
//		}
//
//		// Autobackup
//		final SwitchPreference enableAutobackup = (SwitchPreference) findPreference("settings_enable_autobackup");
//		if (enableAutobackup != null) {
//			enableAutobackup.setOnPreferenceChangeListener((preference, newValue) -> {
//				if ((Boolean) newValue) {
//					new MaterialDialog.Builder(activity)
//							.content(R.string.settings_enable_automatic_backup_dialog)
//							.positiveText(R.string.confirm)
//							.negativeText(R.string.cancel)
//							.onPositive((dialog, which) -> {
//								PermissionsHelper.requestPermission(getActivity(), Manifest.permission
//										.WRITE_EXTERNAL_STORAGE, R
//										.string.permission_external_storage, activity.findViewById(R.ID
//										.crouton_handle), () -> {
//									BackupHelper.startBackupService(AUTO_BACKUP_DIR);
//									enableAutobackup.setChecked(true);
//								});
//							})
//							.build().show();
//				} else {
//					enableAutobackup.setChecked(false);
//				}
//				return false;
//			});
//		}

    Preference importFromSpringpad = findPreference("settings_import_from_springpad");
    if (importFromSpringpad != null) {
      importFromSpringpad.setOnPreferenceClickListener(arg0 -> {
        Intent intent;
        intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        if (!IntentChecker.isAvailable(getActivity(), intent, null)) {
          Toast.makeText(getActivity(), R.string.feature_not_available_on_this_device,
              Toast.LENGTH_SHORT).show();
          return false;
        }
        startActivityForResult(intent, SPRINGPAD_IMPORT);
        return false;
      });
    }

//		Preference syncWithDrive = findPreference("settings_backup_drive");
//		importFromSpringpad.setOnPreferenceClickListener(new OnPreferenceClickListener() {
//			@Override
//			public boolean onPreferenceClick(Preference arg0) {
//				Intent intent;
//				intent = new Intent(Intent.ACTION_GET_CONTENT);
//				intent.addCategory(Intent.CATEGORY_OPENABLE);
//				intent.setType("application/zip");
//				if (!IntentChecker.isAvailable(getActivity(), intent, null)) {
//					Crouton.makeText(getActivity(), R.string.feature_not_available_on_this_device,
// ONStyle.ALERT).show();
//					return false;
//				}
//				startActivityForResult(intent, SPRINGPAD_IMPORT);
//				return false;
//			}
//		});

    // Swiping action
    final SwitchPreference swipeToTrash = findPreference("settings_swipe_to_trash");
    if (swipeToTrash != null) {
      if (Prefs.getBoolean("settings_swipe_to_trash", false)) {
        swipeToTrash.setChecked(true);
        swipeToTrash
            .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_2));
      } else {
        swipeToTrash.setChecked(false);
        swipeToTrash
            .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_1));
      }
      swipeToTrash.setOnPreferenceChangeListener((preference, newValue) -> {
        if ((Boolean) newValue) {
          swipeToTrash
              .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_2));
        } else {
          swipeToTrash
              .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_1));
        }
        return true;
      });
    }

    // Show uncategorized notes in menu
    final SwitchPreference showUncategorized = findPreference(PREF_SHOW_UNCATEGORIZED);
    if (showUncategorized != null) {
      showUncategorized.setOnPreferenceChangeListener((preference, newValue) -> true);
    }

    // Show Automatically adds location to new notes
    final SwitchPreference autoLocation = findPreference(PREF_AUTO_LOCATION);
    if (autoLocation != null) {
      autoLocation.setOnPreferenceChangeListener((preference, newValue) -> true);
    }

    // Maximum video attachment size
    final EditTextPreference maxVideoSize = findPreference("settings_max_video_size");
    if (maxVideoSize != null) {
      maxVideoSize.setSummary(getString(R.string.settings_max_video_size_summary) + ": "
          + Prefs.getString("settings_max_video_size", getString(R.string.not_set)));
      maxVideoSize.setOnPreferenceChangeListener((preference, newValue) -> {
        maxVideoSize
            .setSummary(getString(R.string.settings_max_video_size_summary) + ": " + newValue);
        Prefs.edit().putString("settings_max_video_size", newValue.toString()).apply();
        return false;
      });
    }

    // Set notes' protection password
    Preference password = findPreference("settings_password");
    if (password != null) {
      password.setOnPreferenceClickListener(preference -> {
        Intent passwordIntent = new Intent(getActivity(), PasswordActivity.class);
        startActivity(passwordIntent);
        return false;
      });
    }

    // Use password to grant application access
    final SwitchPreference passwordAccess = findPreference("settings_password_access");
    if (passwordAccess != null) {
      if (Prefs.getString(PREF_PASSWORD, null) == null) {
        passwordAccess.setEnabled(false);
        passwordAccess.setChecked(false);
      } else {
        passwordAccess.setEnabled(true);
      }
      passwordAccess.setOnPreferenceChangeListener((preference, newValue) -> {
        PasswordHelper.requestPassword(getActivity(), passwordConfirmed -> {
          if (passwordConfirmed.equals(Result.SUCCEED)) {
            passwordAccess.setChecked((Boolean) newValue);
          }
        });
        return true;
      });
    }

    // Languages
    ListPreference lang = findPreference("settings_language");
    if (lang != null) {
      String languageName = getResources().getConfiguration().locale.getDisplayName();
      lang.setSummary(
          languageName.substring(0, 1).toUpperCase(getResources().getConfiguration().locale)
              + languageName.substring(1));
      lang.setOnPreferenceChangeListener((preference, value) -> {
        LanguageHelper.updateLanguage(getActivity(), value.toString());
        SystemHelper.restartApp(getActivity().getApplicationContext(), MainActivity.class);
        return false;
      });
    }

    // Application's colors
    final ListPreference colorsApp = findPreference("settings_colors_app");
    if (colorsApp != null) {
      int colorsAppIndex = colorsApp.findIndexOfValue(Prefs.getString("settings_colors_app",
          PREF_COLORS_APP_DEFAULT));
      String colorsAppString = getResources().getStringArray(R.array.colors_app)[colorsAppIndex];
      colorsApp.setSummary(colorsAppString);
      colorsApp.setOnPreferenceChangeListener((preference, newValue) -> {
        int colorsAppIndex1 = colorsApp.findIndexOfValue(newValue.toString());
        String colorsAppString1 = getResources()
            .getStringArray(R.array.colors_app)[colorsAppIndex1];
        colorsApp.setSummary(colorsAppString1);
        Prefs.edit().putString("settings_colors_app", newValue.toString()).apply();
        colorsApp.setValueIndex(colorsAppIndex1);
        return false;
      });
    }

    // Checklists
    final ListPreference checklist = findPreference("settings_checked_items_behavior");
    if (checklist != null) {
      int checklistIndex = checklist
          .findIndexOfValue(Prefs.getString("settings_checked_items_behavior", "0"));
      String checklistString = getResources()
          .getStringArray(R.array.checked_items_behavior)[checklistIndex];
      checklist.setSummary(checklistString);
      checklist.setOnPreferenceChangeListener((preference, newValue) -> {
        int checklistIndex1 = checklist.findIndexOfValue(newValue.toString());
        String checklistString1 = getResources().getStringArray(R.array.checked_items_behavior)
            [checklistIndex1];
        checklist.setSummary(checklistString1);
        Prefs.edit().putString("settings_checked_items_behavior", newValue.toString()).apply();
        checklist.setValueIndex(checklistIndex1);
        return false;
      });
    }

    // Widget's colors
    final ListPreference colorsWidget = findPreference("settings_colors_widget");
    if (colorsWidget != null) {
      int colorsWidgetIndex = colorsWidget
          .findIndexOfValue(Prefs.getString("settings_colors_widget",
              PREF_COLORS_APP_DEFAULT));
      String colorsWidgetString = getResources()
          .getStringArray(R.array.colors_widget)[colorsWidgetIndex];
      colorsWidget.setSummary(colorsWidgetString);
      colorsWidget.setOnPreferenceChangeListener((preference, newValue) -> {
        int colorsWidgetIndex1 = colorsWidget.findIndexOfValue(newValue.toString());
        String colorsWidgetString1 = getResources()
            .getStringArray(R.array.colors_widget)[colorsWidgetIndex1];
        colorsWidget.setSummary(colorsWidgetString1);
        Prefs.edit().putString("settings_colors_widget", newValue.toString()).apply();
        colorsWidget.setValueIndex(colorsWidgetIndex1);
        return false;
      });
    }

    // Ringtone selection
    final Preference ringtone = findPreference("settings_notification_ringtone");
    if (ringtone != null) {
      ringtone.setOnPreferenceClickListener(arg0 -> {
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
          new NotificationsHelper(getContext()).updateNotificationChannelsSound();
        } else {
          Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, DEFAULT_NOTIFICATION_URI);

          String existingValue = Prefs.getString("settings_notification_ringtone", null);
          if (existingValue != null) {
            if (existingValue.length() == 0) {
              // Select "Silent"
              intent.putExtra(EXTRA_RINGTONE_EXISTING_URI, (Uri) null);
            } else {
              intent.putExtra(EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existingValue));
            }
          } else {
            // No ringtone has been selected, set to the default
            intent.putExtra(EXTRA_RINGTONE_EXISTING_URI, DEFAULT_NOTIFICATION_URI);
          }

          startActivityForResult(intent, RINGTONE_REQUEST_CODE);
        }

        return false;
      });
    }

    // Notification snooze delay
    final EditTextPreference snoozeDelay = findPreference("settings_notification_snooze_delay");
    if (snoozeDelay != null) {
      String snooze = Prefs.getString("settings_notification_snooze_delay", PREF_SNOOZE_DEFAULT);
      snooze = TextUtils.isEmpty(snooze) ? PREF_SNOOZE_DEFAULT : snooze;
      snoozeDelay.setSummary(snooze + " " + getString(R.string.minutes));
      snoozeDelay.setOnPreferenceChangeListener((preference, newValue) -> {
        String snoozeUpdated = TextUtils.isEmpty(String.valueOf(newValue)) ? PREF_SNOOZE_DEFAULT
            : String.valueOf(newValue);
        snoozeDelay.setSummary(snoozeUpdated + " " + getString(R.string.minutes));
        Prefs.edit().putString("settings_notification_snooze_delay", snoozeUpdated).apply();
        return false;
      });
    }

    // NotificationServiceListener shortcut
    final Preference norificationServiceListenerPreference = findPreference(
        "settings_notification_service_listener");
    if (norificationServiceListenerPreference != null) {
      getPreferenceScreen().removePreference(norificationServiceListenerPreference);
    }

    // Changelog
    Preference changelog = findPreference("settings_changelog");
    if (changelog != null) {
      changelog.setOnPreferenceClickListener(arg0 -> {
        ChangelogHelper.showChangelog(getActivity());
        return false;
      });
      changelog.setSummary(AppVersionHelper.getCurrentAppVersionName(getActivity()));
    }

    // Settings reset
    Preference resetData = findPreference("reset_all_data");
    if (resetData != null) {
      resetData.setOnPreferenceClickListener(arg0 -> {

        new MaterialDialog.Builder(getContext())
            .content(R.string.reset_all_data_confirmation)
            .positiveText(R.string.confirm)
            .onPositive((dialog, which) -> {
              Prefs.edit().clear().apply();
              File db = getActivity().getDatabasePath(DATABASE_NAME);
              StorageHelper.delete(getActivity(), db.getAbsolutePath());
              File attachmentsDir = StorageHelper.getAttachmentDir();
              StorageHelper.delete(getActivity(), attachmentsDir.getAbsolutePath());
              File cacheDir = StorageHelper.getCacheDir(getActivity());
              StorageHelper.delete(getActivity(), cacheDir.getAbsolutePath());
              Prefs.edit().clear().apply();
              SystemHelper.restartApp(getActivity().getApplicationContext(), MainActivity.class);
            }).build().show();

        return false;
      });
    }

    // Logs on files activation
    final SwitchPreference enableFileLogging = findPreference(PREF_ENABLE_FILE_LOGGING);
    if (enableFileLogging != null) {
      enableFileLogging.setOnPreferenceChangeListener((preference, newValue) -> {
        if ((Boolean) newValue) {
          PermissionsHelper
              .requestPermission(getActivity(), permission.WRITE_EXTERNAL_STORAGE, R
                      .string.permission_external_storage,
                  getActivity().findViewById(R.id.crouton_handle),
                  () -> enableFileLogging.setChecked(true));
        } else {
          enableFileLogging.setChecked(false);
        }
        return false;
      });
    }

    // Instructions
    Preference instructions = findPreference("settings_tour_show_again");
    if (instructions != null) {
      instructions.setOnPreferenceClickListener(arg0 -> {
        new MaterialDialog.Builder(getActivity())
            .content(getString(R.string.settings_tour_show_again_summary) + "?")
            .positiveText(R.string.confirm)
            .onPositive((dialog, which) -> {
              startActivity(new Intent(getContext(), IntroActivity.class));
            }).build().show();
        return false;
      });
    }
  }

  private void startIntentForScopedStorage(int intentRequestCode) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    startActivityForResult(intent, intentRequestCode);
  }

  @TargetApi(VERSION_CODES.O)
  private DocumentFileCompat scopedStorageFolderChoosen() throws ExternalStorageProviderException {
    var backupFolderUri = Prefs.getString(PREF_BACKUP_FOLDER_URI, null);
    try {
      var backupFolder = DocumentFileCompat.Companion.fromTreeUri(getContext(),
          Uri.parse(backupFolderUri));
      if (backupFolder == null || !backupFolder.canWrite()) {
        throw new ExternalStorageProviderException("Can't write into " + backupFolder);
      }
      return backupFolder;
    } catch (SecurityException | NullPointerException e) {
      throw new ExternalStorageProviderException(e);
    }
  }

  private void importNotes() {
    importNotes(null);
  }

  private void importNotes(DocumentFileCompat documentFile) {
    String[] backupsArray;
    if (documentFile != null) {
      backupsArray = Observable.from(documentFile.listFiles()).map(DocumentFileCompat::getName).toList()
          .toBlocking().single().toArray(new String[0]);
    } else {
      backupsArray = StorageHelper.getOrCreateExternalStoragePublicDir().list();
    }

    if (ArrayUtils.isEmpty(backupsArray)) {
      ((SettingsActivity) getActivity()).showMessage(R.string.no_backups_available, ONStyle.WARN);
    } else {
      final List<String> backups = asList(backupsArray);
      reverse(backups);

      MaterialAlertDialogBuilder importDialog = new MaterialAlertDialogBuilder(getActivity())
          .setTitle(R.string.settings_import)
          .setSingleChoiceItems(backupsArray, -1, (dialog, position) -> {
          })
          .setPositiveButton(R.string.data_import_message, (dialog, which) -> {
            int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();

            if (position == -1) {
              Toast.makeText(getContext(), R.string.nothing_selected, Toast.LENGTH_LONG).show();
              return;
            }

            String backupSelected = backups.get(position);

            new MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.confirm_restoring_backup)
                .setMessage(backupSelected + "\n\n" + getString(R.string.confirm_restoring_backup_warning))
                .setPositiveButton(R.string.confirm, (dialog1, which1) -> {
                  // An IntentService will be launched to accomplish the import task
                  Intent service = new Intent(getActivity(),
                      DataBackupIntentService.class);
                  service.setAction(DataBackupIntentService.ACTION_DATA_IMPORT);
                  service.putExtra(DataBackupIntentService.INTENT_BACKUP_NAME, backupSelected);
                  getActivity().startService(service);
                }).show();
          })
          .setNegativeButton(R.string.delete, (dialog, which) -> {
            int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();

            if (position == -1) {
              Toast.makeText(getContext(), R.string.nothing_selected, Toast.LENGTH_LONG).show();
              return;
            }

            String backupSelected = backups.get(position);

            new MaterialDialog.Builder(getActivity())
                .title(R.string.confirm_removing_backup)
                .content(backupSelected)
                .positiveText(R.string.confirm)
                .onPositive((dialog12, which1) -> {
                  Intent service = new Intent(getActivity(),
                      DataBackupIntentService.class);
                  service.setAction(DataBackupIntentService.ACTION_DATA_DELETE);
                  service.putExtra(DataBackupIntentService.INTENT_BACKUP_NAME, backupSelected);
                  getActivity().startService(service);
                }).build().show();
          });

      importDialog.show();
    }
  }


  private void exportNotes() {
    View v = getActivity().getLayoutInflater().inflate(R.layout.dialog_backup_layout, null);

    String[] backupsArray = StorageHelper.getOrCreateExternalStoragePublicDir().list();
    final List<String> backups = ArrayUtils.isEmpty(backupsArray) ? emptyList() : asList(backupsArray);

    // Sets default export file name
    SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_EXPORT);
    String fileName = sdf.format(Calendar.getInstance().getTime());
    final EditText fileNameEditText = v.findViewById(R.id.export_file_name);
    final TextView backupExistingTextView = v.findViewById(R.id.backup_existing);
    fileNameEditText.setHint(fileName);
    fileNameEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
        // Nothing to do
      }


      @Override
      public void beforeTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
        // Nothing to do
      }


      @Override
      public void afterTextChanged(Editable arg0) {

        if (backups.contains(arg0.toString())) {
          backupExistingTextView.setText(R.string.backup_existing);
        } else {
          backupExistingTextView.setText("");
        }
      }
    });

    new MaterialAlertDialogBuilder(getContext())
        .setTitle(R.string.data_export_message)
        .setView(v)
        .setPositiveButton(R.string.confirm, (dialog, which) -> {
          String backupName = TextUtils.isEmpty(fileNameEditText.getText().toString()) ?
              fileNameEditText.getHint().toString() : fileNameEditText.getText().toString();
          BackupHelper.startBackupService(backupName);
        }).show();
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (resultCode == Activity.RESULT_OK) {
      switch (requestCode) {
        case SPRINGPAD_IMPORT:
          Uri filesUri = intent.getData();
          String path = FileHelper.getPath(getActivity(), filesUri);
          // An IntentService will be launched to accomplish the import task
          Intent service = new Intent(getActivity(), DataBackupIntentService.class);
          service.setAction(SpringImportHelper.ACTION_DATA_IMPORT_SPRINGPAD);
          service.putExtra(SpringImportHelper.EXTRA_SPRINGPAD_BACKUP, path);
          getActivity().startService(service);
          break;

        case RINGTONE_REQUEST_CODE:
          Uri uri = intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
          String notificationSound = uri == null ? null : uri.toString();
          Prefs.edit().putString("settings_notification_ringtone", notificationSound).apply();
          break;

        case ACCESS_DATA_FOR_EXPORT:
          BackupHelper.saveScopedStorageUriInPreferences(intent);
          exportNotes();
          break;

        case ACCESS_DATA_FOR_IMPORT:
          var backupDocumentFile = BackupHelper.saveScopedStorageUriInPreferences(intent);
          importNotes(backupDocumentFile);
          break;

        default:
          LogDelegate.e("Wrong element choosen: " + requestCode);
      }
    }
  }



}
