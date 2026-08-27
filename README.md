# Optimisation des performances — Hibernate/JPA & PostgreSQL


## 1. Méthode de travail

Ne jamais optimiser à l'aveugle. Ordre recommandé :

1. **Mesurer** : `pg_stat_statements` (requêtes cumulées les plus coûteuses), `hibernate.generate_statistics` + `stats.getPrepareStatementCount()` (nombre de requêtes par cas d'usage), profiler applicatif (JProfiler).
2. **Corriger le N+1** en premier — gain le plus rapide et souvent le plus important.
3. **Vérifier les plans d'exécution** (`EXPLAIN (ANALYZE, BUFFERS)`) des requêtes identifiées comme lentes.
4. **Ajouter les index manquants**, retester.
5. Envisager cache applicatif ou dénormalisation seulement en dernier recours.


## 2. Hibernate / JPA

### 2.1 Résoudre le N+1

Le problème le plus fréquent en pratique. Solutions, par ordre de préférence :

- **`JOIN FETCH`** en JPQL pour une relation ciblée et connue à l'avance.
- **`@EntityGraph`** pour charger dynamiquement les relations utiles selon le cas d'usage (évite de multiplier les méthodes de repository avec des `JOIN FETCH` différents).
- **`@BatchSize(size = N)`** sur une relation `@OneToMany`/`@ManyToMany` : au lieu d'un `SELECT` par entité parente, Hibernate regroupe les chargements en `SELECT ... WHERE id IN (?, ?, ..., ?)`.
- **`@Fetch(FetchMode.SUBSELECT)`** : charge la collection de toutes les entités du contexte de persistance en une seule sous-requête — pertinent quand on a déjà chargé une liste d'entités parentes et qu'on veut leurs collections en un coup.

⚠️ **Piège à éviter** : ne jamais empiler plusieurs `JOIN FETCH` sur des collections différentes dans la même requête → produit cartésien qui multiplie le volume de données transférées. Préférer une requête séparée par collection (c'est déjà le pattern utilisé dans `MovieRepositoryExtendedImpl.getMoviesWithAwardsAndReviews`, qui fait deux requêtes distinctes plutôt qu'un double `JOIN FETCH`).

### 2.2 Fetch modes

- **`LAZY` par défaut** sur toutes les relations (`@OneToMany`, `@ManyToMany`, et idéalement aussi `@ManyToOne`/`@OneToOne`).
- **`EAGER`** uniquement si la relation est systématiquement utilisée partout où l'entité est chargée (rare).
- **`spring.jpa.open-in-view=false`** — déjà activé dans ce projet. Essentiel : évite les lazy-loads implicites dans la couche web et les connexions JDBC retenues inutilement longtemps pendant le rendu de la réponse.

### 2.3 Pagination

- Toujours paginer les requêtes retournant des listes potentiellement grandes.
- `LIMIT/OFFSET` fonctionne bien pour un **offset faible** (les premières pages), mais **se dégrade sur la pagination profonde** : PostgreSQL doit scanner et écarter les lignes sautées avant d'atteindre la page demandée, même avec un bon index (l'index ne permet pas de "sauter" directement à la ligne N).
- Sur un gros volume avec navigation profonde, préférer le **keyset/seek pagination** :
  ```sql
  WHERE (sort_col, id) > (:lastSortVal, :lastId)
  ORDER BY sort_col, id
  LIMIT :pageSize
  ```
- Trier sur une colonne non indexée avec un grand volume de lignes (`Sort.by("name")` sur une table à 1M+ lignes) force un tri externe coûteux — indexer la colonne de tri.

### 2.4 Batch processing (INSERT/UPDATE)

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=25
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
```

⚠️ **Point critique, souvent oublié** : **`GenerationType.IDENTITY` casse le batching**. Hibernate doit connaître l'ID généré par la base *avant* d'empiler l'insert suivant → avec `IDENTITY`, chaque insert part individuellement, quel que soit `batch_size`. Il faut **`GenerationType.SEQUENCE`** avec un `allocationSize > 1` (pooled optimizer) pour que le batching soit réellement efficace.

→ Ce projet utilise déjà `@GeneratedValue(strategy = GenerationType.SEQUENCE)` sur `Movie`, `Genre`, `Award`, `Review` : bien positionné sur ce point.

Pour les imports/purges massifs : `StatelessSession` ou requêtes JPQL bulk (`UPDATE`/`DELETE`) plutôt que charger puis modifier des milliers d'entités managées (évite le 1er niveau de cache et le dirty checking).

### 2.5 SQL natif pour les cas complexes

Hibernate excelle en CRUD, mais pour :
- les agrégations complexes,
- les CTE,
- les fonctions fenêtrées (`OVER`),
- les requêtes analytiques,

→ le SQL natif PostgreSQL est souvent plus lisible et plus performant que l'équivalent JPQL/Criteria.

### 2.6 Transactions

- **`@Transactional(readOnly = true)`** sur les méthodes de lecture : évite le dirty checking, réduit le CPU, évite les flushs automatiques inutiles.
- ⚠️ **Ne route pas automatiquement vers un read-replica.** `readOnly = true` pose seulement un flag Spring (`TransactionSynchronizationManager.isCurrentTransactionReadOnly()`) que Hibernate exploite pour ses propres optimisations. Pour router réellement vers une réplique en lecture, il faut une `AbstractRoutingDataSource` explicite qui lit ce flag.
- Garder les transactions courtes ; jamais d'I/O externe (appel HTTP, etc.) dans une transaction ouverte (impact direct sur MVCC, voir §4.1).



## 3. Spring Boot / HikariCP

### 3.1 Configuration HikariCP

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=1200000
spring.datasource.hikari.connection-timeout=20000
spring.datasource.hikari.leak-detection-threshold=60000
spring.datasource.hikari.validation-timeout=3000
```

Dimensionner `maximum-pool-size` selon la formule (Brett Wooldridge, auteur de HikariCP) :
```
connections = ((core_count * 2) + effective_spindle_count)
```
Un pool trop grand dégrade les performances PostgreSQL (context switching, contention sur les verrous internes) — ce n'est pas "plus de connexions = plus de débit".

### 3.2 Réduire les round-trips JDBC

```properties
spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true
```
Évite qu'Hibernate vérifie/positionne l'autocommit à chaque emprunt de connexion au pool.

Côté driver PostgreSQL, activer dans l'URL JDBC :
```
?reWriteBatchedInserts=true
```
Transforme les batchs Hibernate en un seul statement multi-valeurs réellement envoyé en un round-trip réseau.

### 3.3 Observabilité

- Logs SQL activés en dev (`spring.jpa.properties.hibernate.format_sql=true`, déjà présent dans ce projet).
- `datasource-proxy` ou p6spy pour logger le SQL réel avec les valeurs bindées (le SQL formaté seul ne montre pas les paramètres).
- **Hypersistence Optimizer** (Vlad Mihalcea) : détecte automatiquement N+1, mauvais fetch, batching non fonctionnel, index manquants.


## 4. PostgreSQL (DBA)

### 4.1 Comprendre MVCC

PostgreSQL ne met jamais à jour une ligne en place : il **insère une nouvelle version** et marque l'ancienne comme obsolète (tuple mort). Conséquences directes :

- Éviter les **transactions longues** (elles empêchent l'autovacuum de nettoyer les tuples morts que la transaction pourrait encore voir → bloat).
- Éviter les `UPDATE`/`DELETE` massifs non batchés (génèrent énormément de tuples morts d'un coup).
- Surveiller le bloat des tables et index à forte rotation.

### 4.2 Indexation

- **B-tree** : cas général — clés primaires, clés étrangères, filtres `WHERE` simples, colonnes de `ORDER BY`.
- **GIN** : colonnes `JSONB`, recherche full-text, `pg_trgm` (recherche par similarité de texte) quand la taille de l'index n'est pas le facteur limitant.
- **GiST** : données géométriques, `pg_trgm` quand la vitesse d'écriture prime sur la taille/vitesse de lecture.
- **Index partiels** (`WHERE deleted = false`) quand une requête filtre toujours sur une sous-population stable.
- ⚠️ Hibernate/JPA ne crée **jamais** d'index sur les colonnes de clé étrangère automatiquement — à créer explicitement en migration Flyway pour chaque `@ManyToOne`/`@JoinColumn` fréquemment filtré ou joint (ex. `Review.movie_id`, `Award.movie_id`, `MovieDetails.movie_id`).

### 4.3 Jointures

- Toujours indexer les colonnes de jointure.
- Préférer les `JOIN` explicites aux sous-requêtes corrélées.
- Vérifier systématiquement les plans via `EXPLAIN (ANALYZE, BUFFERS)` : `Seq Scan` vs `Index Scan`, nombre de buffers lus, estimation vs réalité du nombre de lignes (`rows=X` planifié vs réel).

### 4.4 VACUUM / ANALYZE [voir le fichier https://github.com/hatemsassi/PerfHibernate/blob/main/vacuum.md ]

- `ANALYZE` obligatoire après tout chargement massif — sans stats à jour, le planner prend de mauvaises décisions (scan séquentiel au lieu d'index scan, mauvais ordre de jointure).
- Autovacuum à surveiller/ajuster sur les tables à forte rotation (`autovacuum_vacuum_cost_limit`, seuils `autovacuum_vacuum_scale_factor`).

### 4.5 Configuration serveur

- `shared_buffers` : ~25 % de la RAM disponible.
- `effective_cache_size` : ~50-75 % de la RAM (indique au planner la taille du cache disque disponible, influence le choix index vs seq scan).
- `work_mem` : attention, multiplié par le nombre d'opérations de tri/hash concurrentes — ne pas mettre une valeur énorme globalement.
- `maintenance_work_mem` : pour accélérer `VACUUM` et la construction d'index.
- `pg_stat_statements` activé en prod — source de vérité pour identifier les requêtes les plus coûteuses **cumulées** (pas seulement la plus lente ponctuellement).
- `log_min_duration_statement` pour tracer en continu les requêtes lentes.

### 4.6 Partitionnement

Utile pour les tables volumineuses (historiques, logs, données à forte rétention). Améliore les scans ciblés, les `DELETE` de masse (drop de partition au lieu de delete ligne par ligne) et l'efficacité du `VACUUM`.



## 5. Architecture globale

### 5.1 Cache de second niveau

- Ehcache / Caffeine / Redis en cache L2 Hibernate.
- Réservé aux données de référence peu volatiles et fréquemment lues (ex. `Genre`, `Certification` dans ce projet) — **pas** pour des entités à forte rotation (`Review`, `Movie` en écriture fréquente).

### 5.2 Read-replicas

Réduit la charge sur le primaire pour les charges de lecture. Nécessite une `AbstractRoutingDataSource` explicite (voir §2.6) — ce n'est pas automatique avec `readOnly=true` seul.


## 6. Checklist de synthèse

### Hibernate / JPA
- [ ] `LAZY` par défaut partout
- [ ] `JOIN FETCH` / `@EntityGraph` pour les cas ciblés, jamais plusieurs `JOIN FETCH` sur des collections différentes en même temps
- [ ] `@BatchSize` / `FetchMode.SUBSELECT` pour les collections
- [ ] Pagination systématique ; keyset pagination au-delà de quelques dizaines de milliers de lignes
- [ ] `GenerationType.SEQUENCE` (pas `IDENTITY`) + `batch_size`/`order_inserts`/`order_updates` pour un batching réellement actif
- [ ] SQL natif pour agrégations complexes / CTE / fenêtres
- [ ] `@Transactional(readOnly = true)` pour les lectures

### Spring Boot / HikariCP
- [ ] Pool HikariCP dimensionné selon la charge réelle, pas au hasard
- [ ] `provider_disables_autocommit=true` + `reWriteBatchedInserts=true`
- [ ] Logs SQL + valeurs bindées activés en dev
- [ ] Statistiques Hibernate activées en dev pour détecter le N+1 en test

### PostgreSQL
- [ ] Index sur toutes les FK, colonnes de `WHERE` fréquentes, colonnes de `ORDER BY`
- [ ] GIN/GiST pour JSONB / recherche texte
- [ ] `EXPLAIN (ANALYZE, BUFFERS)` systématique sur les requêtes suspectes
- [ ] `ANALYZE` après chargement massif
- [ ] Autovacuum surveillé, transactions longues évitées (MVCC)
- [ ] Partitionnement si volumétrie importante
- [ ] `pg_stat_statements` + `log_min_duration_statement` activés
