/*
 * Copyright 2014 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.systemTray.swingUI;

import java.awt.MouseInfo;
import java.awt.Point;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import dorkbox.systemTray.Tray;
import dorkbox.systemTray.jna.linux.AppIndicator;
import dorkbox.systemTray.jna.linux.AppIndicatorInstanceStruct;
import dorkbox.systemTray.jna.linux.GEventCallback;
import dorkbox.systemTray.jna.linux.GdkEventButton;
import dorkbox.systemTray.jna.linux.Gobject;
import dorkbox.systemTray.jna.linux.Gtk;
import dorkbox.systemTray.util.ImageUtils;
import dorkbox.util.SwingUtil;

/**
 * Class for handling all system tray interactions.
 * specialization for using app indicators in ubuntu unity
 *
 * Derived from
 * Lantern: https://github.com/getlantern/lantern/ Apache 2.0 License Copyright 2010 Brave New Software Project, Inc.
 *
 * AppIndicators DO NOT support anything other than plain gtk-menus, because of how they use dbus so no tooltips AND no custom widgets
 *
 *
 *
 * As a result of this decision by Canonical, we have to resort to hacks to get it to do what we want.  BY NO MEANS IS THIS PERFECT.
 *
 *
 * We still cannot have tooltips, but we *CAN* have custom widgets in the menu (because it's our swing menu now...)
 *
 *
 * It would be too much work to re-implement AppIndicators, or even to use LD_PRELOAD + restart service to do what we want.
 *
 * As a result, we have some wicked little hacks which are rather effective (but have a small side-effect of very briefly
 * showing a blank menu)
 *
 * // What are AppIndicators?
 * http://unity.ubuntu.com/projects/appindicators/
 *
 *
 * // Entry-point into appindicators
 * http://bazaar.launchpad.net/~unity-team/unity/trunk/view/head:/services/panel-main.c
 *
 *
 * // The idiocy of appindicators
 * https://bugs.launchpad.net/screenlets/+bug/522152
 *
 * // Code of how the dbus menus work
 * http://bazaar.launchpad.net/~dbusmenu-team/libdbusmenu/trunk.16.10/view/head:/libdbusmenu-gtk/client.c
 * https://developer.ubuntu.com/api/devel/ubuntu-12.04/c/dbusmenugtk/index.html
 *
 * // more info about trying to put widgets into GTK menus
 * http://askubuntu.com/questions/16431/putting-an-arbitrary-gtk-widget-into-an-appindicator-indicator
 *
 * // possible idea on how to get GTK widgets into GTK menus
 * https://launchpad.net/ido
 * http://bazaar.launchpad.net/~canonical-dx-team/ido/trunk/view/head:/src/idoentrymenuitem.c
 * http://bazaar.launchpad.net/~ubuntu-desktop/ido/gtk3/files
 */
@SuppressWarnings("Duplicates")
public
class _AppIndicatorTray extends Tray implements SwingUI {
    private volatile AppIndicatorInstanceStruct appIndicator;
    private boolean isActive = false;
    private volatile Runnable popupRunnable;

    // This is required if we have JavaFX or SWT shutdown hooks (to prevent us from shutting down twice...)
    private AtomicBoolean shuttingDown = new AtomicBoolean();

    // necessary to prevent GC on these objects
    @SuppressWarnings("FieldCanBeLocal")
    private GEventCallback gtkCallback;


    // necessary to provide a menu (which we draw over) so we get the "on open" event when the menu is opened via clicking
    private Pointer dummyMenu;

    // is the system tray visible or not.
    private volatile boolean visible = true;
    private volatile File imageFile;

    // has the name already been set for the indicator?
    private volatile boolean setName = false;

    // appindicators DO NOT support anything other than PLAIN gtk-menus (which we hack to support swing menus)
    //   they ALSO do not support tooltips, so we cater to the lowest common denominator
    // trayIcon.setToolTip("app name");

    public
    _AppIndicatorTray(final SystemTray systemTray) {
        super();

        Gtk.startGui();

        Gtk.dispatch(new Runnable() {
            @Override
            public
            void run() {
                // we initialize with a blank image
                File image = ImageUtils.getTransparentImage(ImageUtils.ENTRY_SIZE);
                String id = System.nanoTime() + "DBST";
                appIndicator = AppIndicator.app_indicator_new(id, image.getAbsolutePath(), AppIndicator.CATEGORY_APPLICATION_STATUS);

                createAppIndicatorMenu();
            }
        });

        Gtk.waitForStartup();

        SwingUtil.invokeLater(new Runnable() {
            @Override
            public
            void run() {
                // we override various methods, because each tray implementation is SLIGHTLY different. This allows us customization.
                final SwingMenu swingMenu = new SwingMenu(null) {
                    @Override
                    public
                    void setEnabled(final MenuItem menuItem) {
                        Gtk.dispatch(new Runnable() {
                            @Override
                            public
                            void run() {
                                boolean enabled = menuItem.getEnabled();

                                if (visible && !enabled) {
                                    // STATUS_PASSIVE hides the indicator
                                    AppIndicator.app_indicator_set_status(appIndicator, AppIndicator.STATUS_PASSIVE);
                                    visible = false;
                                }
                                else if (!visible && enabled) {
                                    AppIndicator.app_indicator_set_status(appIndicator, AppIndicator.STATUS_ACTIVE);
                                    visible = true;
                                }
                            }
                        });
                    }

                    @Override
                    public
                    void setImage(final MenuItem menuItem) {
                        imageFile = menuItem.getImage();
                        if (imageFile == null) {
                            return;
                        }

                        Gtk.dispatch(new Runnable() {
                            @Override
                            public
                            void run() {
                                AppIndicator.app_indicator_set_icon(appIndicator, imageFile.getAbsolutePath());

                                if (!isActive) {
                                    isActive = true;

                                    AppIndicator.app_indicator_set_status(appIndicator, AppIndicator.STATUS_ACTIVE);

                                    // now we have to setup a way for us to catch the "activation" click on this menu. Must be after the menu is set
                                    hookMenuOpen();
                                }
                            }
                        });


                        // needs to be on EDT
                        SwingUtil.invokeLater(new Runnable() {
                            @Override
                            public
                            void run() {
                                ((TrayPopup) _native).setTitleBarImage(imageFile);
                            }
                        });
                    }

                    @Override
                    public
                    void setText(final MenuItem menuItem) {
                        // no op
                    }

                    @Override
                    public
                    void setShortcut(final MenuItem menuItem) {
                        // no op
                    }

                    @Override
                    public
                    void remove() {
                        if (!shuttingDown.getAndSet(true)) {
                            // must happen asap, so our hook properly notices we are in shutdown mode
                            final AppIndicatorInstanceStruct savedAppIndicator = appIndicator;
                            appIndicator = null;

                            Gtk.dispatch(new Runnable() {
                                @Override
                                public
                                void run() {
                                    // STATUS_PASSIVE hides the indicator
                                    AppIndicator.app_indicator_set_status(savedAppIndicator, AppIndicator.STATUS_PASSIVE);
                                    Pointer p = savedAppIndicator.getPointer();
                                    Gobject.g_object_unref(p);
                                }
                            });

                            // does not need to be called on the dispatch (it does that)
                            Gtk.shutdownGui();

                            super.remove();
                        }
                    }
                };

                TrayPopup popupMenu = (TrayPopup) swingMenu._native;
                popupMenu.pack();
                popupMenu.setFocusable(true);
                popupMenu.setOnHideRunnable(new Runnable() {
                    @Override
                    public
                    void run() {
                        if (appIndicator == null) {
                            // if we are shutting down, don't hook the menu again
                            return;
                        }

                        // Such ugly hacks to get AppIndicator support properly working. This is so horrible I am ashamed.
                        Gtk.dispatchAndWait(new Runnable() {
                            @Override
                            public
                            void run() {
                                createAppIndicatorMenu();
                                hookMenuOpen();
                            }
                        });
                    }
                });

                popupRunnable = new Runnable() {
                    @Override
                    public
                    void run() {
                        Point point = MouseInfo.getPointerInfo()
                                               .getLocation();

                        TrayPopup popupMenu = (TrayPopup) swingMenu._native;
                        popupMenu.doShow(point, SystemTray.DEFAULT_TRAY_SIZE);
                    }
                };

                bind(swingMenu, null, systemTray);
            }
        });
    }

    private
    void hookMenuOpen() {
        // now we have to setup a way for us to catch the "activation" click on this menu. Must be after the menu is set
        PointerByReference menuServer = new PointerByReference();
        PointerByReference rootMenuItem = new PointerByReference();

        Gobject.g_object_get(appIndicator.getPointer(), "dbus-menu-server", menuServer, null);
        Gobject.g_object_get(menuServer.getValue(), "root-node", rootMenuItem, null);

        gtkCallback = new GEventCallback() {
            @Override
            public
            void callback(Pointer notUsed, final GdkEventButton event) {
                Gtk.gtk_menu_shell_deactivate(dummyMenu);
                SwingUtil.invokeLater(popupRunnable);
            }
        };

        Gobject.g_signal_connect_object(rootMenuItem.getValue(), "about-to-show", gtkCallback, null, 0);
    }

    private
    void createAppIndicatorMenu() {
        dummyMenu = Gtk.gtk_menu_new();
        Pointer item = Gtk.gtk_image_menu_item_new_with_mnemonic("");
        Gtk.gtk_menu_shell_append(dummyMenu, item);
        Gtk.gtk_widget_show_all(item);

        AppIndicator.app_indicator_set_menu(appIndicator, dummyMenu);

        if (!setName) {
            setName = true;

            // by default, the title/name of the tray icon is "java". We are the only java-based tray icon, so we just use that.
            // If you change "SystemTray" to something else, make sure to change it in extension.js as well

            // can cause (potentially)
            // GLib-GIO-CRITICAL **: g_dbus_connection_emit_signal: assertion 'object_path != NULL && g_variant_is_object_path (object_path)' failed
            // Gdk-CRITICAL **: IA__gdk_window_thaw_toplevel_updates_libgtk_only: assertion 'private->update_and_descendants_freeze_count > 0' failed

            // necessary for gnome icon detection/placement because we move tray icons around by title. This is hardcoded
            //  in extension.js, so don't change it

            // additionally, this is required to be set HERE (not somewhere else)
            AppIndicator.app_indicator_set_title(appIndicator, "SystemTray");
        }
    }

    @Override
    public final
    boolean hasImage() {
        return imageFile != null;
    }
}
