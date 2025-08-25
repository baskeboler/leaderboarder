(ns leaderboarder.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]))

(rf/reg-event-db
 :initialize
 (fn [_ _]
   {:token nil
    :response nil}))

(rf/reg-event-db
 :set-response
 (fn [db [_ res]]
   (assoc db :response res)))

(rf/reg-event-db
 :set-token
 (fn [db [_ token]]
   (assoc db :token token)))

(rf/reg-fx
 :fetch
 (fn [{:keys [method url data token on-success]}]
   (-> (js/fetch url
                 (clj->js
                  (merge {:method method
                          :headers (cond-> {"Content-Type" "application/json"}
                                     token (assoc "Authorization" (str "Bearer " token)))}
                         (when data {:body (-> data clj->js js/JSON.stringify)}))))
       (.then (fn [r] (.json r)))
       (.then (fn [res]
                (let [m (js->clj res :keywordize-keys true)]
                  (rf/dispatch (conj on-success m)))))
       (.catch (fn [e]
                 (rf/dispatch [:set-response {:error (.-message e)}])))))

(rf/reg-event-fx
 :api-request
 (fn [{:keys [db]} [_ method url data on-success]]
   {:fetch {:method method
            :url url
            :data data
            :token (:token db)
            :on-success on-success}}))

(rf/reg-event-fx
 :register
 (fn [_ [_ form]]
   {:dispatch [:api-request "POST" "/users" form [:set-response]]}))

(rf/reg-event-fx
 :login
 (fn [_ [_ creds]]
   {:dispatch [:api-request "POST" "/login" creds [:login-success]]}))

(rf/reg-event-db
 :login-success
 (fn [db [_ res]]
   (-> db
       (assoc :token (:token res))
       (assoc :response res))))

(rf/reg-sub :token (fn [db _] (:token db)))
(rf/reg-sub :response (fn [db _] (:response db)))

(defn input [attrs]
  [:input (merge {:class "border p-2"} attrs)])

(defn button [text on-click]
  [:button {:class "bg-blue-500 text-white px-4 py-2 rounded"
            :on-click on-click}
   text])

(defn register-form []
  (let [form (r/atom {:username "" :password "" :geography "" :sex "" :age_group ""})]
    (fn []
      [:div {:class "mb-6"}
       [:h3 {:class "text-lg font-bold mb-2"} "Register"]
       [:div {:class "flex flex-col space-y-2"}
        [input {:placeholder "Username" :value (:username @form)
                :on-change #(swap! form assoc :username (.. % -target -value))}]
        [input {:type "password" :placeholder "Password" :value (:password @form)
                :on-change #(swap! form assoc :password (.. % -target -value))}]
        [input {:placeholder "Geography" :value (:geography @form)
                :on-change #(swap! form assoc :geography (.. % -target -value))}]
        [input {:placeholder "Sex" :value (:sex @form)
                :on-change #(swap! form assoc :sex (.. % -target -value))}]
        [input {:placeholder "Age group" :value (:age_group @form)
                :on-change #(swap! form assoc :age_group (.. % -target -value))}]
        [button "Create user" #(rf/dispatch [:register @form])]]]))))

(defn login-form []
  (let [form (r/atom {:username "" :password ""})]
    (fn []
      [:div {:class "mb-6"}
       [:h3 {:class "text-lg font-bold mb-2"} "Login"]
       [:div {:class "flex flex-col space-y-2"}
        [input {:placeholder "Username" :value (:username @form)
                :on-change #(swap! form assoc :username (.. % -target -value))}]
        [input {:type "password" :placeholder "Password" :value (:password @form)
                :on-change #(swap! form assoc :password (.. % -target -value))}]
        [button "Login" #(rf/dispatch [:login @form])]]])))

(defn app []
  (let [token @(rf/subscribe [:token])
        response @(rf/subscribe [:response])]
    [:div {:class "space-y-6"}
     [register-form]
     (if token
       [:p {:class "font-semibold"} "Logged in."]
       [login-form])
     [:pre {:class "bg-gray-100 p-2"} (pr-str response)]]))

(defn ^:export init []
  (rf/dispatch-sync [:initialize])
  (rdom/render [app] (.getElementById js/document "app")))

(init)

