(ns app.core
  (:require [io.pedestal.http :as http]
            [com.walmartlabs.lacinia.pedestal :as pedestal]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia :as lacinia]
            [clojure.java.jdbc :as jdbc]
            [com.walmartlabs.lacinia.parser.schema :as parser.schema]
            [clojure.java.io :as io]))


(defn init-db!
  "Conectado no :host, executa:
- Deleta o :dbname, se existir
- Cria o :dbname
- Instala o schema em :dbname"
  [db]
  (jdbc/execute! (assoc db :dbname "")
                 (str "DROP DATABASE IF EXISTS " (:dbname db) ";")
                 {:transaction? false})
  (jdbc/execute! (assoc db :dbname "")
                 (str "CREATE DATABASE " (:dbname db) ";")
                 {:transaction? false})
  (jdbc/execute! db (slurp (io/resource "schema.sql"))))



(defn resolve-eu
  [{::jdbc/keys [db]} {:keys [nome]} _]
  (-> (jdbc/query db ["SELECT id,nome FROM usuario WHERE nome = ?"
                      nome])
      first))

(defn me-cria
  [{::jdbc/keys [db] :as ctx} {:keys [nome]} _]
  (jdbc/insert! db :usuario {:nome nome})
  (resolve-eu ctx {:nome nome} _))


(defn novo-todo
  [{::jdbc/keys [db] :as ctx} {:keys [descricao nome_usuario]} _]
  (jdbc/with-db-transaction [tx db]
    (jdbc/insert! tx :todo {:descricao descricao})
    (jdbc/execute! tx ["INSERT INTO todos_do_usuario (id, usuario, todo)
                         VALUES (DEFAULT,
                                 (SELECT usuario.id FROM usuario WHERE usuario.nome = ?),
                                 CAST(currval(pg_get_serial_sequence('todo','id')) AS integer))"
                       nome_usuario]))
  (resolve-eu ctx {:nome nome_usuario} _))


(defn deleta-todo
  [{::jdbc/keys [db] :as ctx} {:keys [id]} _]
  (let [nome (jdbc/with-db-transaction [tx db]
               (let [{:keys [nome ref-id]} (-> (jdbc/query tx ["SELECT todos_do_usuario.id AS \"ref-id\", usuario.nome
                                                                FROM todos_do_usuario
                                                                  INNER JOIN usuario
                                                                  ON todos_do_usuario.usuario = usuario.id
                                                                WHERE todos_do_usuario.todo = CAST(? AS integer)"
                                                               id])
                                               first)]
                 (jdbc/delete! tx :todos_do_usuario ["id = ?" ref-id])
                 (jdbc/delete! tx :todo ["id = CAST(? AS integer)" id])
                 nome))]
    (resolve-eu ctx {:nome nome} _)))



(defn resolve-todos
  [{::jdbc/keys [db]} _ {:keys [id]}]
  (jdbc/query db ["SELECT *
                   FROM todo
                     INNER JOIN todos_do_usuario
                     on todos_do_usuario.usuario = ?"
                  id]))

(defn get-compiled-schema
  []
  (-> (io/resource "schema.graphql")
      (slurp)
      (parser.schema/parse-schema {:resolvers {:Usuario      {:todos resolve-todos}
                                               :QueryRoot    {:eu resolve-eu}
                                               :MutationRoot {:meCria     me-cria
                                                              :novoTodo   novo-todo
                                                              :deletaTodo deleta-todo}}})
      (schema/compile)))

(def app-context
  {::jdbc/db {:dbtype "postgresql"
              :dbname "app"
              :host   "localhost"
              :user   "postgres"}})

(defonce runnable-service (atom nil))

(defn run-dev
  "Para chamar no repl, desenvolvimento"
  [& args]
  (swap! runnable-service (fn [srv]
                            (when srv
                              (http/stop srv))
                            (-> #(get-compiled-schema)
                                (pedestal/service-map {:env         :dev
                                                       :app-context app-context
                                                       :graphiql    true})
                                #_(http/dev-interceptors)
                                (http/create-server)
                                (http/start)))))


(defn -main
  "chamar via clj -m app.core"
  [& args]
  (swap! runnable-service (fn [srv]
                            (when srv
                              (http/stop srv))
                            (-> (get-compiled-schema)
                                (pedestal/service-map {:app-context app-context
                                                       :env         :prod})
                                (http/create-server)
                                (http/start)))))


