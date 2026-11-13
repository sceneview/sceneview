# La logique de toute la démo SceneView — et ce qui disparaît

Écrit le 12/11/2026 par la session d'audit (Fable 5.2). « From scratch » au sens de
l'audit : d'abord à quoi sert chaque écran, puis la liste de ce qu'on supprime.

Sources : les chiffres d'usage sur 28 jours
([`sceneview-demo-usage-2026-11-12.md`](../sceneview-demo-usage-2026-11-12.md), GA4, par
la session SceneView) et un recensement des 43 écrans de démo dans le code. Je n'ai pas
manipulé l'app moi-même.

Les mots de Thomas (12/11) : « cet onglet AR, il sert strictement à rien » ; « pourquoi
il y a un menu qui est comme ça dans un écran et pas comme ça dans l'autre » ; « tu
gardes des coquilles moches, qui servent plus à rien ». Et le 27/09 : « moins de samples,
plus de réglages ».

## Ce que disent les chiffres

Base : les vraies personnes seulement, 28 jours au 12/11 (section 7 du document
d'usage). Le robot de pré-lancement de Play et les appareils de labo sont retirés ; ils
pesaient plus de 80 % des premiers chiffres.

- **325 personnes, 558 sessions.** Deux tiers ne viennent qu'une fois, un tiers revient
  (96 personnes). La première minute décide, et l'accueil doit aussi servir celui qui
  revient.
- **Deux sessions sur trois ouvrent une démo.**
- **Deux démos écrasent tout** : Model Viewer (481 ouvertures) et Place in AR (362).
  Ensuite : 3D Scan (98, et c'est là qu'on reste le plus longtemps), Faces (72),
  Cosmos (54), Geospatial (50), Materials (42).
- **L'onglet AR View est ouvert dans 43 % des sessions**, par 178 personnes sur 325. Ce
  n'est donc pas « personne n'y va » : les gens veulent l'AR, c'est leur deuxième envie.
  Ce qu'ils trouvent derrière l'onglet, en revanche, est une deuxième grille de démos et
  un bouton de permission. Ce qui est en trop, c'est l'écran, pas l'AR.
- **L'onglet About** : 20 % des sessions.
- **Douze démos ont moins de 15 ouvertures en 28 jours**, et trois n'en ont aucune.
- **Cloud Anchors** n'était ouvert que par le robot (24 vraies ouvertures, pas 574).
  **Geospatial**, lui, est vraiment ouvert : 42 personnes.
- **La recherche** : 4,5 % des sessions. « Browse online models » : 2,9 %.
- **Erreurs** : 28 personnes (8,6 %), dont 11 sur l'accueil. Un seul vrai crash
  aujourd'hui : tout modèle glTF avec un matériau « volume » arrête l'app depuis la
  4.66 (sélecteur de Place in AR, visionneuse, Thumbnails). Correctif en 4.69.2.
  L'accueil n'est pas en cause.

**Ce que je ne sais pas** : d'où les gens ouvrent Place in AR (l'accueil ou l'onglet AR
View), et ce que vaut Matter, pas encore publiée. Ce n'est pas mesuré.

## La logique : un écran d'entrée, six sections, un seul habillage

**1. L'app n'a plus d'onglets.** Elle a un accueil, et des démos. Une barre de
navigation sert à passer entre des lieux de même importance ; ici il n'y en a qu'un.

**2. L'accueil sert à une chose : éblouir, puis envoyer vers la bonne démo en un
geste.** C'est le Pavillon et ses six onglets ([design](sceneview-accueil.md)).

**3. Six sections, dans l'ordre de ce que les gens ouvrent.** L'AR passe en deuxième
onglet, visible sans faire défiler la rangée. Dans chaque section, la première ligne est
la démo la plus ouverte, et les suivantes sont rangées par ouvertures.

| Section | En premier | Ensuite |
|---|---|---|
| Models | Model Viewer | Animation, Night circuit |
| AR | Place in AR | Faces, Geospatial, Image tracking, Measure, Body tracking, Orbit, Occlusion, Point & Ask |
| Scan | 3D Scan | Photographic 3D |
| Materials | Matter | Materials, Compose in 3D, Lighting, Shapes, Spatial audio |
| Play | Cosmos | Rolling balls, Pool |
| Tools | AR diagnostics | Debug overlay, Thumbnails, Cloud Anchors |

Environ 26 entrées au lieu de 43 dans le code (GA4 en compte 45, avec deux identifiants
hors catalogue).

Deux paris, pas des mesures : Matter en tête de Materials (pas encore publiée) et Pool
dans Play (4 ouvertures quand il était rangé en AR).

Geospatial reste en AR, c'est vérifié le 12/11 par la session SceneView : la version du
store embarque sa clé, 41 vraies personnes l'ont ouvert et 20 s'en sont servies. Sans
couverture, la démo le dit par un bandeau. Ses erreurs de démarrage ne sont pas
mesurées, et il n'a pas été essayé dehors.

**4. Une démo = un seul habillage, partout le même.**
- **En haut** : retour, titre, plein écran. Rien d'autre. Jamais de menu à trois points.
- **En bas, au pouce** : trois actions propres à la démo au plus, puis toujours les deux
  mêmes boutons à la même place : « Source » et « Settings ». Le bouton AR, quand il
  existe, est le bouton coloré.
- **Les messages d'état** (attente, conseil, mesure) : un seul endroit, la pastille
  au-dessus du dock, avec un seul fond. Plus rien sous le titre.
- **Les modes** : un seul composant pour changer de mode.
- **Aucune permission** n'est demandée avant que la personne ait choisi de lancer.

**5. Ce qui est pour les développeurs est rangé dans Tools**, jamais mis en avant, et
annonce ce qu'il lui faut (clé, appareil) avant de démarrer.

## Ce qui disparaît

**La navigation**
1. L'onglet **AR View**. L'AR devient le deuxième onglet de l'accueil, avec Place in AR
   en tête : toujours à un geste, sans écran intermédiaire. Son écran d'entrée « AR Experiences », son bouton « Grant Camera Access » posé
   avant tout choix, et sa grille de démos en double partent avec lui.
2. L'onglet **About**. Il devient une feuille, ouverte par le bouton « i » en haut de
   l'accueil : soutenir SceneView, documentation, source, autres apps, confidentialité,
   licences, nouveautés.
3. La **barre de navigation du bas**.
4. L'icône **étincelles** et la carte « What's new ». Une seule entrée, dans la feuille
   About ; un point sur le « i » quand il y a du neuf.
5. Le **lien Playground** dans About.
6. La **demande de notifications** au lancement : elle arrive avant qu'on ait rien vu.
   Le réglage reste dans About.

**L'accueil**
7. Les **trois choix**, « Try next », « Featured », le carrousel à pages, les puces de
   section : les six onglets les remplacent.
8. La ligne **« Browse online models »** : elle rejoint le sélecteur de modèles du Model
   Viewer, où l'on choisit déjà un modèle.
9. Le téléchargement **« HD scenes · 310 MB »** rangé dans About : proposé seulement là où
   l'on voit la différence (le sélecteur de modèles), ou retiré.
10. **Tout texte posé sur la scène**, et les jetons du ciel peint.

**Le catalogue**
11. Les **sept démos de diagnostic AR** deviennent une seule, « AR diagnostics », avec un
    mode par vue : surfaces (deux démos), points (deux démos), carte de profondeur,
    stabilisation d'image, sémantique de scène.
12. **3D Scan et Room model** sont le même écran : une seule entrée.
13. **Occlusion** : profondeur et personnes dans une seule démo, deux modes.
14. **Shapes** : Geometry, Custom geometry et Lines & paths dans une seule démo.
15. **Contact shadows** devient un réglage de Lighting.
16. **Camera gestures**, **Picking & collision** et **Camera paths** (7, 4 et 9
    ouvertures) deviennent des réglages ou un mode du Model Viewer.
17. **ML object label** (8 ouvertures), **AR XR** et **my-demo** (aucune) sortent du
    catalogue. **Finger drawing** aussi : 8 ouvertures, par 3 personnes.
18. **Cloud Anchors** quitte toute mise en avant et va dans Tools : 24 vraies ouvertures.
    S'il lève encore des erreurs après correction, il est masqué.

**L'habillage des démos**
19. Le **menu à trois points** des deux écrans du 3D Scan.
20. Les **écrans qui dessinent leur propre habillage** : pages d'entrée du 3D Scan,
    écran d'enregistrement, visionneuse de la galerie avec sa croix.
21. Le **sélecteur de mode maison** du Model Viewer.
22. Les **trois styles de bandeau en haut** (verre, noir transparent, couleur du thème)
    et les textes sous le titre : tout passe dans la pastille du bas.
23. L'écran **Thumbnails sans dock**, d'où l'on ne peut ni réinitialiser ni voir la
    source.

**Les images**
24. Les **vignettes d'essai** : le renard, la petite voiture, la voiture verte sur le
    drap, les objets seuls sur fond gris. Chaque vignette est un vrai rendu de l'app,
    dans une scène.
25. Les **aperçus du Model Viewer** qui montrent encore le casque retiré pour sa
    licence.

**Le code**
26. Les identifiants de démos retirées encore présents, le fichier mal nommé
    `ARSceneGeometryDemo.kt`, et le commentaire du scaffold qui contredit le code.

## Ce qu'on garde, parce que ça sert

- Le scaffold unique des démos : il existe et 42 démos sur 43 l'utilisent. On le
  resserre, on ne le refait pas.
- « View source », mais visible : dans le dock, plus au fond des réglages.
- La recherche, discrète, au bout des onglets.
- Les paliers de qualité et le repli en image fixe.

## Trois choses à faire avec, pour ne pas reconstruire à l'aveugle

- **Crashlytics : lu le 12/11** par la session SceneView. L'accueil ne plante pas ; le
  seul crash actuel est le matériau « volume », corrigé en 4.69.2.
- **Chiffres d'usage refaits sans le robot : fait le 12/11** (section 7). Cette page
  s'appuie dessus. À garder comme filtre pour toute lecture future.
- **Mesurer le nouvel accueil** : quel onglet est choisi, si la scène est touchée, d'où
  vient l'ouverture d'une démo. Aujourd'hui rien de cela n'est lisible.

## L'ordre

1. L'accueil Pavillon et la suppression des onglets (c'est le même chantier).
2. L'habillage unique des démos.
3. Le regroupement du catalogue et les vignettes.

Chaque étape se montre à Thomas par un enregistrement d'écran, pas par une liste.
