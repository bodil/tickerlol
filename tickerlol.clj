;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this file,
;; You can obtain one at http://mozilla.org/MPL/2.0/.

; You need to run this once to initialise the gconf schema:
; $ gconftool --type=string --set /apps/tickerlol/tickers NASDAQ:GOOG

(ns ticker
  (:require [clojure.lang.persistenthashmap :only [fromDict]]
            [os.path]
            [urllib2 :only [urlopen quote]]
            [threading :only [Thread]]
            [json] [appindicator] [gconf]
            [gtk] [gtk.gdk] [glib]))

(def GCONF_TICKERS "/apps/tickerlol/tickers")

(defn tickers
  "Given a space separated string of ticker names, fetch ticker feeds for each."
  [names]
  (for [id (seq (.split names " "))
        :let [url (str "http://finance.google.com/finance/info?client=ig&q=" id)]]
    (-> (json/loads (-> (urlopen url) (.read) (.replace "//" "")))
        (first) (fromDict))))

(defn label
  "Given a sequence of ticker feeds, return a string suitable for user output."
  [tickers]
  (.join " " (map #(str (% "t") " " (or (% "el_cur") (% "l_cur"))) tickers)))

(defn cheap-future
  "Run a function in a new daemon thread."
  [f]
  (doto (Thread. nil f)
    (py/setattr "daemon" true)
    (.start)))

(defn run-repeatedly
  "Run a function repeatedly with an interval given in seconds."
  [interval f]
  ((fn timer []
     (f)
     (glib/timeout_add_seconds interval timer))))

(defn get-ticker-config [client]
  (.get_string client GCONF_TICKERS))

(defn update-tickers
  "Spawn a thread to fetch ticker data and update the indicator."
  [indicator client]
  (cheap-future
   #(.set_label indicator (label (tickers (get-ticker-config client))))))

(defn make-menu
  "Construct the indicator menu."
  [settings-dialog]
  (let [menu (gtk/Menu.)
        settings-item (gtk/MenuItem. "Settings...")
        quit-item (gtk/MenuItem. "Quit")]
    (.append menu settings-item)
    (.append menu quit-item)
    (doto settings-item
      (.connect "activate" (fn [_] (.run settings-dialog)))
      (.show))
    (doto quit-item
      (.connect "activate" (fn [_] (gtk/main_quit)))
      (.show))
    menu))

(defn glade-load
  "Load a Glade file and instantiate it."
  [glade-file]
  (doto (gtk/Builder.)
    (.add_from_file glade-file)))

(gtk.gdk/threads_init)

(definterface ISettingsDialog
  (on_ok_clicked [this widget])
  (on_cancel_clicked [this widget])
  (set-tickers [this tickers])
  (run [this]))

(deftype SettingsDialog [glade client]
  ISettingsDialog
  (on_ok_clicked [this widget]
    (.set_string client GCONF_TICKERS
                 (.get_text (.get_object glade "ticker_entry")))
    (doto (.get_object glade "dialog")
      (.hide)
      (.response true)))
  (on_cancel_clicked [this widget]
    (doto (.get_object glade "dialog")
      (.hide)
      (.response false)))
  (set-tickers [this tickers]
    (.set_text (.get_object glade "ticker_entry") (or tickers "")))
  (run [this]
    (let [dialog (.get_object glade "dialog")]
      (.show dialog)
      (.run dialog))))

(defn make-settings-dialog [client]
  (let [glade (glade-load
               (os.path/join (os.path/dirname __file__) "dialog.glade"))
        dialog (SettingsDialog. glade client)]
    (.connect_signals glade dialog)
    dialog))

(let [indicator (appindicator/Indicator "tickerlol" ""
                                appindicator/CATEGORY_APPLICATION_STATUS)
      client (gconf/client_get_default)
      settings-dialog (make-settings-dialog client)]

  (.add_dir client "/apps/tickerlol" gconf/CLIENT_PRELOAD_NONE)

  (doto indicator
    (.set_status appindicator/STATUS_ACTIVE)
    (.set_menu (make-menu settings-dialog)))

  (let [on-tickers-changed
        (fn [& _] (.set-tickers settings-dialog (get-ticker-config client)))]
    (on-tickers-changed)
    (.notify_add client GCONF_TICKERS on-tickers-changed))

  (run-repeatedly 10 #(update-tickers indicator client))

  (gtk/main))
