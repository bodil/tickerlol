#!/usr/bin/env python

# Run this to manually setup gconf schema: gconftool --type=string --set /apps/tickerlol/tickers NASDAQ:GOOG

import sys, os, json, re, gobject, glib, gtk, appindicator, gconf
from urllib2 import urlopen, quote
from threading import Thread

GCONF_TICKERS = "/apps/tickerlol/tickers"

def tickers(s):
    return [json.loads(urlopen("http://finance.google.com/finance/info?client=ig&q=%s" % quote(id)).read().replace("//", ""))[0] for id in s.split(" ")]

def label(tickers):
    return " ".join(["%(t)s %(l_cur)s" % ticker for ticker in tickers])

def quit(*args):
    sys.exit(0)

class Updater(Thread):
    def __init__(self, indicator, gconf_client):
        Thread.__init__(self)
        gtk.gdk.threads_init()
        self.daemon = True
        self.indicator = indicator
        self.gconf_client = gconf_client
        self.settings_dialog = settings_dialog

    def run(self):
        self.indicator.set_label(label(tickers(self.gconf_client.get_string(GCONF_TICKERS))))

class SettingsDialog:
    def __init__(self, gconf_client):
        self.gconf_client = gconf_client
        self.builder = gtk.Builder()
        self.builder.add_from_file(os.path.join(os.path.dirname(__file__), "dialog.glade"))
        self.builder.connect_signals(self)
        self.dialog = self.builder.get_object("dialog")
        self.entry = self.builder.get_object("ticker_entry")

    def on_ok_clicked(self, widget):
        self.dialog.hide()
        self.gconf_client.set_string(GCONF_TICKERS, self.entry.get_text())
        self.dialog.response(True)

    def on_cancel_clicked(self, widget):
        self.dialog.hide()
        self.dialog.response(False)

    def set_tickers(self, tickers):
        if tickers:
            self.entry.set_text(tickers)
        else:
            self.entry.set_text("")

    def run(self):
        self.dialog.show()
        return self.dialog.run()

if __name__ == "__main__":
    i = appindicator.Indicator("tickerlol", "", appindicator.CATEGORY_APPLICATION_STATUS)
    i.set_status(appindicator.STATUS_ACTIVE)

    gconf_client = gconf.client_get_default()
    gconf_client.add_dir("/apps/tickerlol", gconf.CLIENT_PRELOAD_NONE)

    settings_dialog = SettingsDialog(gconf_client)

    def update_tickers(client, *args, **kwargs):
        tickers = client.get_string(GCONF_TICKERS)
        settings_dialog.set_tickers(tickers)
        Updater(i, gconf_client).start()
    gconf_client.notify_add(GCONF_TICKERS, update_tickers)
    update_tickers(gconf_client)

    def on_menu_settings(*args):
        settings_dialog.run()

    menu = gtk.Menu()
    settings_item = gtk.MenuItem("Settings...")
    settings_item.connect("activate", on_menu_settings)
    menu.append(settings_item)
    settings_item.show()
    quit_item = gtk.MenuItem("Quit")
    quit_item.connect("activate", quit)
    menu.append(quit_item)
    quit_item.show()
    i.set_menu(menu)

    def timer(*args):
        Updater(i, gconf_client).start()
        glib.timeout_add_seconds(10, timer)

    timer()

    gtk.main()
