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
package dorkbox.systemTray.linux;

import static dorkbox.systemTray.SystemTray.TIMEOUT;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.jna.Pointer;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuEntry;
import dorkbox.systemTray.SystemTray;
import dorkbox.systemTray.SystemTrayMenuAction;
import dorkbox.systemTray.linux.jna.Gobject;
import dorkbox.systemTray.linux.jna.Gtk;

class GtkMenu extends Menu implements MenuEntry {
    // menu entry that this menu is attached to. Will be NULL when it's the system tray
    private final GtkEntryItem menuEntry;

    // must ONLY be created at the end of delete!
    volatile Pointer _native;

    // have to make sure no other methods can call obliterate, delete, or create menu once it's already started
    private boolean obliterateInProgress = false;

    // called on dispatch
    GtkMenu(final SystemTray systemTray, final GtkMenu parent) {
        super(systemTray, parent);

        if (parent != null) {
            this.menuEntry = new GtkEntryItem(parent, null);
            // by default, no callback on a menu entry means it's DISABLED. we have to undo that, because we don't have a callback for menus
            menuEntry.setEnabled(true);
        } else {
            this.menuEntry = null;
        }
    }

    /**
     * Necessary to guarantee all updates occur on the dispatch thread
     */
    @Override
    protected
    void dispatch(final Runnable runnable) {
        Gtk.dispatch(runnable);
    }

    /**
     * Necessary to guarantee all updates occur on the dispatch thread
     */
    protected
    void dispatchAndWait(final Runnable runnable) {
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        Gtk.dispatch(new Runnable() {
            @Override
            public
            void run() {
                try {
                    runnable.run();
                } finally {
                    countDownLatch.countDown();
                }
            }
        });

        // this is slightly different than how swing does it. We have a timeout here so that we can make sure that updates on the GUI
        // thread occur in REASONABLE time-frames, and alert the user if not.
        try {
            if (!countDownLatch.await(TIMEOUT, TimeUnit.SECONDS)) {
                if (SystemTray.DEBUG) {
                    SystemTray.logger.error("Event dispatch queue took longer than " + TIMEOUT + " seconds to complete. Please adjust " +
                                            "`SystemTray.TIMEOUT` to a value which better suites your environment.");
                } else {
                    throw new RuntimeException("Event dispatch queue took longer than " + TIMEOUT + " seconds to complete. Please adjust " +
                                               "`SystemTray.TIMEOUT` to a value which better suites your environment.");
                }
            }
        } catch (InterruptedException e) {
            SystemTray.logger.error("Error waiting for dispatch to complete.", new Exception());
        }
    }


    public
    void shutdown() {
        dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                obliterateMenu();

                Gtk.shutdownGui();
            }
        });
    }

    /**
     * Enables, or disables the sub-menu entry.
     */
    @Override
    public
    void setEnabled(final boolean enabled) {
        if (enabled) {
            Gtk.gtk_widget_set_sensitive(menuEntry._native, Gtk.TRUE);
        } else {
            Gtk.gtk_widget_set_sensitive(menuEntry._native, Gtk.FALSE);
        }
    }

    @Override
    public
    void addSeparator() {
        dispatch(new Runnable() {
            @Override
            public
            void run() {
                // some GTK libraries DO NOT let us add items AFTER the menu has been attached to the indicator.
                // To work around this issue, we destroy then recreate the menu every time something is changed.
                synchronized (menuEntries) {
                    // some GTK libraries DO NOT let us add items AFTER the menu has been attached to the indicator.
                    // To work around this issue, we destroy then recreate the menu every time something is changed.
                    deleteMenu();

                    GtkEntry menuEntry = new GtkEntrySeparator(GtkMenu.this);
                    menuEntries.add(menuEntry);

                    createMenu();
                }
            }
        });
    }

    @Override
    public
    void setShortcut(final char key) {
        menuEntry.setShortcut(key);
    }

    @Override
    public
    String getText() {
        return menuEntry.getText();
    }

    @Override
    public
    void setText(final String newText) {
        menuEntry.setText(newText);
    }

    @Override
    public
    void setImage(final File imageFile) {
        menuEntry.setImage(imageFile);
    }

    @Override
    public
    void setImage(final String imagePath) {
        menuEntry.setImage(imagePath);
    }

    @Override
    public
    void setImage(final URL imageUrl) {
        menuEntry.setImage(imageUrl);
    }

    @Override
    public
    void setImage(final String cacheName, final InputStream imageStream) {
        menuEntry.setImage(cacheName, imageStream);
    }

    @Override
    public
    void setImage(final InputStream imageStream) {
        menuEntry.setImage(imageStream);
    }

    @Override
    public
    boolean hasImage() {
        return menuEntry.hasImage();
    }

    // NO OP.
    @Override
    public
    void setCallback(final SystemTrayMenuAction callback) {
    }

    /**
     * Called inside the gdk_threads block
     */
    protected
    void onMenuAdded(final Pointer menu) {
        // only needed for AppIndicator
    }

    // some GTK libraries DO NOT let us add items AFTER the menu has been attached to the indicator.
    // To work around this issue, we destroy then recreate the menu every time something is changed.
    /**
     * Deletes the menu, and unreferences everything in it. ALSO recreates ONLY the menu object.
     */
    void deleteMenu() {
        if (obliterateInProgress) {
            return;
        }

        if (_native != null) {
            // have to remove all other menu entries
            synchronized (menuEntries) {
                for (int i = 0; i < menuEntries.size(); i++) {
                    MenuEntry menuEntry__ = menuEntries.get(i);

                    if (menuEntry__ instanceof GtkEntry) {
                        GtkEntry entry = (GtkEntry) menuEntry__;

                        Gobject.g_object_force_floating(entry._native);
                        Gtk.gtk_container_remove(_native, entry._native);
                    }
                    else if (menuEntry__ instanceof GtkMenu) {
                        GtkMenu subMenu = (GtkMenu) menuEntry__;

                        Gobject.g_object_force_floating(subMenu.menuEntry._native);
                        Gtk.gtk_container_remove(_native, subMenu.menuEntry._native);
                    }
                }

                Gtk.gtk_widget_destroy(_native);
            }
        }

        if (getParent() != null) {
            ((GtkMenu) getParent()).deleteMenu();
        }

        // makes a new one
        _native = Gtk.gtk_menu_new();

        // binds sub-menu to entry (if it exists! it does not for the root menu)
        if (menuEntry != null) {
            Gtk.gtk_menu_item_set_submenu(menuEntry._native, _native);
        }
    }

    // some GTK libraries DO NOT let us add items AFTER the menu has been attached to the indicator.
    // To work around this issue, we destroy then recreate the menu every time something is changed.
    void createMenu() {
        if (obliterateInProgress) {
            return;
        }

        if (getParent() != null) {
            ((GtkMenu) getParent()).createMenu();
        }

        boolean hasImages = false;

        // now add back other menu entries
        synchronized (menuEntries) {
            for (MenuEntry menuEntry__ : menuEntries) {
                hasImages |= menuEntry__.hasImage();
            }

            for (MenuEntry menuEntry__ : menuEntries) {
                // the menu entry looks FUNKY when there are a mis-match of entries WITH and WITHOUT images
                if (menuEntry__ instanceof GtkEntry) {
                    GtkEntry entry = (GtkEntry) menuEntry__;
                    entry.setSpacerImage(hasImages);

                    // will also get:  gsignal.c:2516: signal 'child-added' is invalid for instance '0x7f1df8244080' of type 'GtkMenu'
                    Gtk.gtk_menu_shell_append(this._native, entry._native);
                    Gobject.g_object_ref_sink(entry._native);  // undoes "floating"
                }
                else if (menuEntry__ instanceof GtkMenu) {
                    GtkMenu subMenu = (GtkMenu) menuEntry__;

                    // will also get:  gsignal.c:2516: signal 'child-added' is invalid for instance '0x7f1df8244080' of type 'GtkMenu'
                    Gtk.gtk_menu_shell_append(this._native, subMenu.menuEntry._native);
                    Gobject.g_object_ref_sink(subMenu.menuEntry._native);  // undoes "floating"

                    if (subMenu.getParent() != GtkMenu.this) {
                        // we don't want to "createMenu" on our sub-menu that is assigned to us directly, as they are already doing it
                        subMenu.createMenu();
                    }
                }
            }

            onMenuAdded(_native);
            Gtk.gtk_widget_show_all(_native);
        }
    }

    /**
     * must be called on the dispatch thread
     *
     * Completely obliterates the menu, no possible way to reconstruct it.
     */
    private
    void obliterateMenu() {
        if (_native != null && !obliterateInProgress) {
            obliterateInProgress = true;

            // have to remove all other menu entries
            synchronized (menuEntries) {
                for (int i = 0; i < menuEntries.size(); i++) {
                    MenuEntry menuEntry__ = menuEntries.get(i);
                    menuEntry__.remove();
                }
                menuEntries.clear();

                Gtk.gtk_widget_destroy(_native);
            }

            obliterateInProgress = false;
        }
    }


    /**
     * Will add a new menu entry, or update one if it already exists
     */
    protected
    MenuEntry addEntry_(final String menuText, final File imagePath, final SystemTrayMenuAction callback) {
        // some implementations of appindicator, do NOT like having a menu added, which has no menu items yet.
        // see: https://bugs.launchpad.net/glipper/+bug/1203888

        if (menuText == null) {
            throw new NullPointerException("Menu text cannot be null");
        }

        // have to wait for the value
        final AtomicReference<MenuEntry> value = new AtomicReference<MenuEntry>();

        dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                synchronized (menuEntries) {
                    MenuEntry menuEntry = get(menuText);
                    if (menuEntry == null) {
                        // some GTK libraries DO NOT let us add items AFTER the menu has been attached to the indicator.
                        // To work around this issue, we destroy then recreate the menu every time something is changed.
                        deleteMenu();

                        menuEntry = new GtkEntryItem(GtkMenu.this, callback);
                        menuEntry.setText(menuText);
                        menuEntry.setImage(imagePath);
                        menuEntries.add(menuEntry);

                        createMenu();
                    } else if (menuEntry instanceof GtkEntryItem) {
                        menuEntry.setText(menuText);
                        menuEntry.setImage(imagePath);
                    }

                    value.set(menuEntry);
                }
            }
        });

        return value.get();
    }

    /**
     * Will add a new menu entry, or update one if it already exists
     */
    protected
    Menu addMenu_(final String menuText, final File imagePath) {
        // some implementations of appindicator, do NOT like having a menu added, which has no menu items yet.
        // see: https://bugs.launchpad.net/glipper/+bug/1203888

        if (menuText == null) {
            throw new NullPointerException("Menu text cannot be null");
        }

        final AtomicReference<Menu> value = new AtomicReference<Menu>();

        dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                synchronized (menuEntries) {
                    MenuEntry menuEntry = get(menuText);
                    if (menuEntry == null) {
                        // some GTK libraries DO NOT let us add items AFTER the menu has been attached to the indicator.
                        // To work around this issue, we destroy then recreate the menu every time something is changed.
                        deleteMenu();

                        GtkMenu subMenu = new GtkMenu(getSystemTray(), GtkMenu.this);
                        subMenu.setText(menuText);
                        subMenu.setImage(imagePath);

                        menuEntries.add(subMenu);

                        value.set(subMenu);

                        createMenu();
                    } else if (menuEntry instanceof GtkMenu) {
                        menuEntry.setText(menuText);
                        menuEntry.setImage(imagePath);

                        value.set(((GtkMenu) menuEntry));
                    }
                }
            }
        });

        return value.get();
    }


    // a child will always remove itself from the parent.
    @Override
    public
    void remove() {
        dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                GtkMenu parent = (GtkMenu) getParent();

                // have to remove from the  parent.menuEntries first
                for (Iterator<MenuEntry> iterator = parent.menuEntries.iterator(); iterator.hasNext(); ) {
                    final MenuEntry entry = iterator.next();
                    if (entry == GtkMenu.this) {
                        iterator.remove();
                        break;
                    }
                }

                // cleans up the menu
                parent.remove__(null);

                // delete all of the children of this submenu (must happen before the menuEntry is removed)
                obliterateMenu();

                // remove the gtk entry item from our parent menu NATIVE components
                // NOTE: this will rebuild the parent menu
                if (menuEntry != null) {
                    menuEntry.remove();
                } else {
                    // have to rebuild the menu now...
                    parent.deleteMenu();
                    parent.createMenu();
                }
            }
        });
    }
}
