(ns arrodes.tui.theme-picker
  "Theme selection/preview behavior; the renderer and composer remain mounted."
  (:require [arrodes.tui.context :as c]
            [arrodes.tui.themes :as themes]
            [arrodes.tui.palette :as palette]))

(defn open! [view]
  (let [manager (:themes view)
        {:keys [packs errors]} (themes/reload! manager)
        index (or (first (keep-indexed #(when (= (:id %2) @(:selected manager)) %1) packs)) 0)]
    (c/action! :open-overlay! view
               {:kind :themes :title "Themes" :query "" :index index
                :hint "↑↓ previews immediately · Enter saves · Esc restores your theme"
                :error (when (seq errors) (str (count errors) " invalid pack(s): " (:message (first errors))))})))

(defn items [view]
  (mapv (fn [pack]
          {:label (str (:name pack) (when (= (:id pack) @(:selected (:themes view))) " · active"))
           :description (or (:description pack) (:id pack)) :search-text (:id pack) :theme pack
           :choose (fn []
                     (try
                       (themes/select! (:themes view) (:id pack))
                       (c/action! :close-overlay! view)
                       (c/notify! view (str "Theme: " (:name pack)))
                       (catch :default failure
                         (c/ui! view assoc-in [:overlay :error] (.-message failure)))))})
        (:packs @(:catalog (:themes view)))))

(defn- theme-screen [overlay]
  (when overlay (if (= :themes (:kind overlay)) overlay (theme-screen (:return-overlay overlay)))))

(defn sync! [view]
  (let [manager (:themes view)
        overlay (theme-screen (get-in (c/state view) [:ui :overlay]))
        pack (when overlay
               (:theme (get (c/action! :overlay-items view overlay) (or (:index overlay) 0))))]
    (palette/apply! (:renderer view) (or pack @(:committed manager)))))
