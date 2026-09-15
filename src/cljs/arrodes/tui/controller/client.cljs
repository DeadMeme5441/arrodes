(ns arrodes.tui.controller.client
  "Controller transport access and shared wire/state helpers."
  (:require
            [arrodes.tui-model :as model]
            [arrodes.tui-rpc :as rpc]))

(declare path-module command-timeout-ms error resolved rejected decode value-field non-nil-map session-id-from operation-id-from workspace same-session? current-client call! mutation! operation-notice)

(def path-module (js/require "path"))


(def command-timeout-ms 30000)

(defn error
  ([code message] (error code message {}))
  ([code message data] (ex-info message (assoc data :code code))))


(defn resolved [value]
  (js/Promise.resolve value))


(defn rejected [value]
  (js/Promise.reject value))


(defn decode [value]
  (model/decode-wire value))


(defn value-field [value key]
  (model/field value key))


(defn non-nil-map [value]
  (into {} (remove (comp nil? val)) value))


(defn session-id-from [state]
  (value-field (get-in state [:view :session]) :id))


(defn operation-id-from [state]
  (let [operation (get-in state [:view :operation])
        status (value-field operation :status)]
    (when (and operation
               (not (contains? #{:completed :failed :cancelled :interrupted}
                               (if (string? status) (keyword status) status))))
      (value-field operation :id))))


(defn workspace [app]
  (.resolve path-module (or (get-in @(:state app) [:view :session :cwd])
                            (get-in app [:options :cwd]) (.cwd js/process))))


(defn same-session? [event sid]
  (= sid (value-field event :session-id)))


(defn current-client [app]
  (or @(:client app)
      (throw (error "not-connected" "TUI backend is not connected" {}))))


(defn call!
  ([app method params] (call! app method params {}))
  ([app method params options]
   (rpc/request! (current-client app) method params
                 (merge {:timeout-ms (or (get-in app [:options :request-timeout-ms])
                                         command-timeout-ms)}
                        options))))


(defn mutation! [app method params]
  (call! app method params {:mutation? true}))


(defn operation-notice [state]
  (let [operation (get-in state [:view :operation])
        error (:error operation)]
    (if (= :failed (:status operation))
      (assoc state :notice {:kind :error
                            :message (or (value-field error :message) "The operation failed.")
                            :data error})
      state)))

