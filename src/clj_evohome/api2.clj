(ns clj-evohome.api2
  (:require [clojure.string :as s]
            [clj-evohome.http :refer :all]
            [cheshire.core :as json]
            [camel-snake-kebab.core :as csk]
            [java-time :as jt]))


(def host-url "https://tccna.honeywell.com")

;; this would be for the new API
(def api-url (str host-url "/WebAPI/emea/api/v1"))

(def auth-url (str host-url "/Auth/OAuth/Token"))

(defn- get-auth-tokens [credentials]
  (let [tokens (-> (http-post auth-url
                              {:form-params credentials
                               :headers {:authorization "Basic NGEyMzEwODktZDJiNi00MWJkLWE1ZWItMTZhMGE0MjJiOTk5OjFhMTVjZGI4LTQyZGUtNDA3Yi1hZGQwLTA1OWY5MmM1MzBjYg=="}})
                   :json)]
    (assoc tokens :expires (jt/plus (jt/local-date-time)
                                    (jt/seconds (:expires-in tokens))))))

(defn- basic-login [username password]
  (get-auth-tokens {:grant_type "password"
                    :scope "EMEA-V1-Basic EMEA-V1-Anonymous EMEA-V1-Get-Current-User-Account"
                    :Username username
                    :Password password}))

(defn- refresh-tokens [tokens]
  (get-auth-tokens {:grant_type "refresh_token"
                    :scope (:scope tokens)
                    :refresh_token (:refresh-token tokens)}))

(def connect)

(defn- ensure-fresh-connection [connection]
  (when (jt/before? (jt/plus (jt/local-date-time)
                             (jt/seconds 15))
                    (-> connection :tokens deref :expires))
    (try
      (swap! (:tokens connection) refresh-tokens)
      connection
      (catch Exception e
        (connect (:username connection)
                 (:password connection)))))
  connection)

(defn- auth-tokens [connection]
  (-> (ensure-fresh-connection connection)
      :tokens
      deref))

(defn connect [username password]
  (let [tokens (basic-login username password)]
    {:tokens (atom tokens)
     :username username
     :password password}))

(defn- make-http-headers [connection]
  (let [{:keys [access-token token-type]} (auth-tokens connection)]
    {:accept "application/json"
     :authorization (str token-type " " access-token)}))

(defn api-call [connection op path & {:as opts}]
  (assert connection)
  (:json (op (str api-url "/" path)
             (merge-http-opts {:headers (make-http-headers connection)}
                              opts))))

(defn user-account-info [connection]
  (api-call connection http-get "userAccount"))

(defn installations-by-user [connection user-id]
  (api-call connection http-get "location/installationInfo"
            :query-params {:includeTemperatureControlSystems true
                           :userId user-id}))

(defn installation-by-location [connection location]
  (api-call connection http-get (str "location/" location "/installationInfo")
            :query-params {:includeTemperatureControlSystems true}))

(defn get-system-status [connection system-id]
  (api-call connection http-get (str "temperatureControlSystem/" system-id "/status")))

(defn set-system-mode [connection system-id mode & {:keys [until]}]
  (api-call connection http-put (str "temperatureControlSystem/" system-id "/mode")
            :form-params {:SystemMode (csk/->camelCaseString mode)
                          :TimeUntil (when until (str until))
                          :Permanent (nil? until)}))

(defn- zone-heat-set-point [connection zone-type zone-id data]
  (api-call connection http-put (str zone-type "/" zone-id "/heatSetPoint")
            :content-type "application/json"
            :body (json/generate-string data {:key-fn csk/->camelCaseString})))

(defn set-zone-temperature [connection zone-id temperature & {:keys [until]}]
  (zone-heat-set-point connection "temperatureZone" zone-id
                       {:SetpointMode (if until
                                        "TemporaryOverride"
                                        "PermanentOverride")
                        :HeatSetpointValue temperature
                        :TimeUntil (when until (str until))}))

(defn cancel-zone-override [connection zone-id]
  (zone-heat-set-point connection "temperatureZone" zone-id
                       {:SetpointMode "FollowSchedule"}))

(defn get-zone-schedule [connection zone-id]
  (api-call connection http-get (str "temperatureZone/" zone-id "/schedule")))

(defn set-zone-schedule [connection zone-id schedule]
  (api-call connection http-put (str "temperatureZone/" zone-id "/schedule")
            :content-type "application/json"
            :body (json/generate-string schedule {:key-fn csk/->camelCaseString})))

(defn get-location-status [connection location-id]
  (api-call connection http-get (str "location/" location-id "/status")
            :query-params {:includeTemperatureControlSystems true}))

(defn get-domestic-hot-water [connection dhw-id]
  (api-call connection http-get (str "domesticHotWater/" dhw-id "/status")))

(defn set-domsetic-hot-water [connection dhw-id state & {:keys [until]}]
  (api-call connection http-put (str "domesticHotWater/" dhw-id "/status")
            :form-params {:Mode (cond (= state :auto) "FollowSchedule"
                                      until "TemporaryOverride"
                                      :else "PermanentOverride")
                          :State (when (not= :auto state) (name state))
                          :UntilTime (when until (str until))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn select-locations [c location-name]
  (->> (user-account-info c)
       :user-id
       (installations-by-user c)
       (filter #(= location-name (get-in % [:location-info :name])))))

(defn select-zones [c location zone]
  (->> (select-locations c location)
       (mapcat :gateways)
       (mapcat :temperature-control-systems)
       (mapcat :zones)
       (filter #(= zone (:name %)))))

(defn set-temperature [c location zone temperature & {:keys [until]}]
  (->> (select-zones c location zone)
       (map :zone-id)
       (run! #(set-zone-temperature c % temperature :until until))))

(defn cancel-override [c location zone]
  (->> (select-zones c location zone)
       (map :zone-id)
       (run! (partial cancel-zone-override c))))

(defn set-mode [c location mode]
  (->> (select-locations c location)
       (mapcat :gateways)
       (mapcat :temperature-control-systems)
       (map :system-id)
       (run! #(set-system-mode c % mode))))

(comment
  (def username "...")
  (def password "...")
  (def c (connect username password))
  (def acc-info (user-account-info c))
  (def insts (installations-by-user c (:user-id acc-info)))
  (def inst1 (installation-by-location c (get-in (first insts) [:location-info :location-id])))
  (def system (-> inst1
                  :gateways
                  first
                  :temperature-control-systems
                  first))
  (get-system-status c (:system-id system))
  (set-system-mode c (:system-id system) :dayoff)
  (def zone (first (:zones system)))
  (def sched (get-zone-schedule c (:zone-id zone)))
  (set-zone-schedule c (:zone-id zone) sched)
  (set-zone-temperature c (:zone-id zone) 17.5)
  (cancel-zone-override c (:zone-id zone))
  (get-location-status c (get-in inst1 [:location-info :location-id])))
