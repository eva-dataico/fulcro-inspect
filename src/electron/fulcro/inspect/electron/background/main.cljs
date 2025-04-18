(ns fulcro.inspect.electron.background.main
  (:require
    [cljs.core.async :as async :refer [<! >!] :refer-macros [go go-loop]]
    ["electron" :as electron]
    ["path" :as path]
    ["electron-settings" :as settings]
    ["url" :as url]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.electron.background.websocket-server :as server]
    [goog.functions :as g.fns]
    [shadow.cljs.modern :refer [js-await]]))

(defn get-setting [k default]
  (let [c (async/chan)]
    (-> (.get settings k)
      (.then
        (fn [v]
          (async/go (>! c (if (nil? v) default v))))))
    c))
(defn set-setting! [k v] (.set settings k v))

(defn save-state! [^js window]
  (mapv (fn [[k v]] (set-setting! (str "BrowserWindow/" k) v))
    (js->clj (.getBounds window))))

(defn toggle-settings-window! []
  (server/send-to-devtool!
    {mk/request '[(devtool/toggle-settings {})]}))

(defn create-window []
  (go
    (let [width   (<! (get-setting "BrowserWindow/width" 800))
          height  (<! (get-setting "BrowserWindow/height" 600))
          x       (<! (get-setting "BrowserWindow/x" 0))
          y       (<! (get-setting "BrowserWindow/y" 0))
          ^js win (electron/BrowserWindow.
                    (clj->js {:width          width
                              :height         height
                              :x              x
                              :y              y
                              :webPreferences #js {:nodeIntegration true
                                                   :preload         (path/join js/__dirname ".." "preload.js")}}))]
      (.loadFile win (path/join js/__dirname ".." "public" "index.html"))
      (let [save-window-state! (g.fns/debounce #(save-state! win) 500)]
        (doto win
          (.on "resize" save-window-state!)
          (.on "move" save-window-state!)
          (.on "close" save-window-state!)))
      (.setApplicationMenu electron/Menu
        (.buildFromTemplate electron/Menu
          ;;FIXME: cmd only if is osx
          (clj->js [{:label   (.-name electron/app)
                     :submenu [{:role "about"}
                               {:type "separator"}
                               {:label       "Settings"
                                :accelerator "cmd+,"
                                :click       #(toggle-settings-window!)}
                               {:type "separator"}
                               {:role "quit"}]}
                    {:label   "Edit"
                     :submenu [{:label "Undo" :accelerator "CmdOrCtrl+Z" :selector "undo:"}
                               {:label "Redo" :accelerator "Shift+CmdOrCtrl+Z" :selector "redo:"}
                               {:type "separator"}
                               {:label "Cut" :accelerator "CmdOrCtrl+X" :selector "cut:"}
                               {:label "Copy" :accelerator "CmdOrCtrl+C" :selector "copy:"}
                               {:label "Paste" :accelerator "CmdOrCtrl+V" :selector "paste:"}
                               {:label "Select All" :accelerator "CmdOrCtrl+A" :selector "selectAll:"}]}
                    {:label   "View"
                     :submenu [{:role "reload"}
                               {:role "forcereload"}
                               {:role "toggledevtools"}
                               {:type "separator"}
                               {:role "resetzoom"}
                               {:role "zoomin" :accelerator "cmd+="}
                               {:role "zoomout"}
                               {:type "separator"}
                               {:role "togglefullscreen"}]}])))
      (server/start! (.-webContents win))))
  nil)

(defn ^:export init []
  (js-await [_ (electron/app.whenReady)]
    (create-window)
    (electron/app.on "activate"
      (fn []
        (when (zero? (alength (electron/BrowserWindow.getAllWindows)))
          (create-window))))))
