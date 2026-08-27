# VACUUM — maintenance PostgreSQL

`VACUUM` est une commande de maintenance essentielle de PostgreSQL. Elle sert à nettoyer les lignes mortes, récupérer de l'espace, mettre à jour des statistiques, et éviter certains problèmes internes. C'est un mécanisme central du fonctionnement de PostgreSQL.

## 🎯 En bref

`VACUUM` sert à nettoyer et optimiser les tables PostgreSQL.

Quand on fait un `UPDATE` ou un `DELETE`, PostgreSQL ne supprime pas physiquement les anciennes lignes : il les marque comme mortes (*dead tuples*).

`VACUUM` passe derrière pour :

- Supprimer les lignes mortes et libérer l'espace pour réutilisation
- Mettre à jour les statistiques pour l'optimiseur de requêtes
- Mettre à jour la *visibility map* (accélère les index-only scans)
- Éviter le *wraparound* des IDs de transaction (sinon la base peut devenir inutilisable !)

## 🧹 Les deux types de VACUUM

### 1. VACUUM standard

- Nettoie les lignes mortes
- Libère l'espace dans la table, mais ne le rend pas au système d'exploitation
- Peut tourner en parallèle des requêtes normales
- Très rapide

### 2. VACUUM FULL

- Réécrit complètement la table dans un nouveau fichier
- Rend l'espace au système d'exploitation
- Très lent
- Nécessite un verrou exclusif (bloque les accès à la table)

## 🔄 Autovacuum : le VACUUM automatique

PostgreSQL lance automatiquement des `VACUUM` via le démon `autovacuum`. Il tourne en arrière-plan et évite d'avoir à lancer `VACUUM` manuellement.

Fréquence ajustable via les paramètres :

- `autovacuum_vacuum_scale_factor`
- `autovacuum_vacuum_threshold`
- `autovacuum_naptime`
- etc.

## 🧪 VACUUM ANALYZE

Combine :

- `VACUUM` → nettoyage
- `ANALYZE` → mise à jour des statistiques pour le planner

Très utilisé dans les scripts de maintenance.

## 📌 Quand faut-il lancer VACUUM ?

Même si `autovacuum` fait le travail, il est utile de lancer `VACUUM` manuellement :

- Après une grosse suppression (`DELETE` massif)
- Après un import massif
- Sur des tables très actives (fort taux d'`UPDATE`/`DELETE`)
- Si l'on observe du bloat (gonflement de table)

## 📊 Résumé rapide

| Type | Effet | Verrou | Vitesse | Rend l'espace OS ? |
|---|---|---|---|---|
| `VACUUM` | Nettoie les lignes mortes | Non | Rapide | ❌ |
| `VACUUM FULL` | Réécrit la table | Oui (exclusif) | Lent | ✅ |
| `VACUUM ANALYZE` | Nettoie + met à jour les stats | Non | Rapide | ❌ |

## 🛠️ Commandes pratiques

### 1. Lancer un VACUUM sur toute la base

Commande de maintenance standard, non bloquante :

```sql
VACUUM;
```

👉 Nettoie toutes les tables de la base, sans bloquer les lectures/écritures.

### 2. Lancer un VACUUM sur une table spécifique

```sql
VACUUM ma_table;
```

### 3. VACUUM ANALYZE (recommandé après gros imports/updates)

Combine nettoyage + mise à jour des statistiques :

```sql
VACUUM ANALYZE ma_table;
```

👉 Très utile pour améliorer les performances du planner après des modifications massives.

### 4. VACUUM VERBOSE (pour voir ce qu'il fait)

Affiche les détails du nettoyage :

```sql
VACUUM VERBOSE ma_table;
```

👉 Pratique pour diagnostiquer le bloat ou vérifier que le vacuum travaille correctement.

### 5. VACUUM FULL (à utiliser avec prudence)

Réécrit complètement la table et rend l'espace au système d'exploitation :

```sql
VACUUM FULL ma_table;
```

⚠️ Bloque la table (verrou `ACCESS EXCLUSIVE`) et est beaucoup plus lent.

### 6. VACUUM avec options modernes (PostgreSQL 17+)

Exemple avec options :

```sql
VACUUM (VERBOSE, ANALYZE, PARALLEL 4) ma_table;
```

👉 Utilise 4 workers pour accélérer le traitement des index (vacuum parallèle).

### 7. Vérifier quelles tables ont besoin d'un VACUUM

Avant de lancer un vacuum, on peut inspecter le nombre de lignes mortes :

```sql
SELECT relname,
       n_live_tup,
       n_dead_tup,
       last_vacuum,
       last_autovacuum
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC;
```

👉 Si `n_dead_tup` est élevé → `VACUUM` recommandé.

### 8. Script de maintenance complet

Script simple à lancer régulièrement :

```sql
VACUUM;
VACUUM ANALYZE;
```

Ou ciblé sur une table :

```sql
VACUUM (VERBOSE, ANALYZE) ma_table;
```
