## 5. Le design v2 : « le Pavillon »

**L'idée en une phrase.** L'accueil est un lieu, et les six sections de la démo sont six
façons de regarder ce même lieu. Le pouce passe d'une section à l'autre, et la scène se
transforme sous ses yeux.

**Pourquoi c'est logique.** SceneView, c'est : afficher un modèle, montrer la matière,
faire bouger, poser chez soi, scanner un lieu, outiller le développeur. Ce sont les six
sections du catalogue. Jusqu'ici l'accueil montrait une belle scène d'un côté et une
liste de l'autre, sans lien. Ici, **la liste pilote la scène**. On ne lit pas ce que
SceneView fait : on le voit arriver au même endroit, six fois.

Images cibles : dossier [`sceneview-accueil-cibles/`](sceneview-accueil-cibles/).

### 5.1 Le lieu

Un petit pavillon d'exposition moderniste, au bord de l'eau. C'est de l'architecture, pas
du jeu vidéo.

- Un toit plat et fin, posé sur des colonnes chromées très minces.
- Un mur libre en marbre vert veiné, un autre en onyx couleur miel.
- Un sol en travertin.
- Un long rideau de velours rouge sombre, qui bouge un peu.
- Un fauteuil en cuir et acier.
- Un socle de pierre, et dessus un casque à reflets (Thomas : « des reflets de fou »),
  pris parmi les modèles sous licence claire.
- Devant, un bassin peu profond qui reflète tout.
- Derrière, le ciel et une ligne d'horizon.

Ce choix n'est pas décoratif :
- **Les quatre matières de la démo Matter y sont chez elles** : marbre, velours, cuir,
  acier brossé. L'accueil et la démo en cours racontent la même chose.
- **Des plans et des dalles, c'est ce qu'un moteur physique rend le mieux** et ce qui se
  construit le plus sûrement : peu de modélisation, de très belles textures, de vrais
  reflets. Le circuit de nuit a été fabriqué de cette façon.
- **C'est le terrain réel de SceneView** : des objets et des meubles montrés dans un
  lieu.

### 5.2 La première seconde

L'écran s'ouvre sur un nuage de points en couleur, comme un scan. En une seconde et
demie, **les points se rassemblent et deviennent le pavillon**, puis la lumière s'allume
et le bassin se met à refléter. C'est le « scan vers 3D » montré en ouverture, avec un
lieu beau au lieu d'un scan brut. Une seule fois par lancement. Au retour d'une démo, le
pavillon est là, tel qu'on l'a laissé.

### 5.3 Ça réagit

- **Incliner le téléphone** : l'écran devient une vitre. On regarde dans le pavillon
  comme par une fenêtre, la perspective se décale, les reflets glissent sur le marbre et
  le chrome. C'est l'effet le plus fort qu'un téléphone puisse donner, et une vidéo ne
  peut pas le faire.
- **Un doigt sur la scène** : on tourne autour du pavillon. Deux secondes après, la
  caméra reprend sa place en douceur.
- **Toucher le bassin** : une onde part du doigt, avec une petite vibration.
- **Glisser d'une section à l'autre** : la caméra suit le pouce, en continu. On peut
  s'arrêter entre deux, revenir en arrière : la scène suit.

### 5.4 Six façons de voir le même lieu

| Section | Ce que la scène fait |
|---|---|
| Models | La vue d'ensemble, puis la caméra vient tourner autour du casque sur son socle. |
| AR | Le sol se couvre des points de détection ; le fauteuil se pose avec son anneau et sa cote « 78 cm ». |
| Scan | Le pavillon redevient un nuage de points, avec le trajet de la caméra dessiné dans l'air. |
| Materials | La caméra s'approche du marbre, du velours et du chrome ; une lumière les balaie. |
| Play | Des billes tombent sur la terrasse, roulent, plongent dans le bassin. |
| Tools | Le même lieu en fil de fer, avec les vrais chiffres du rendu. |

L'ordre est celui de l'usage réel : le Model Viewer puis Place in AR sont, de loin, ce
que les gens ouvrent le plus. L'AR est donc le deuxième onglet, visible sans faire
défiler. Il raconte aussi une suite : un objet, l'objet posé chez soi, le lieu scanné.

Le passage d'une vue à l'autre balaie l'écran d'un bord à l'autre, en suivant le pouce.

### 5.5 L'écran

- **La scène : les 60 % du haut**, bord à bord, sous la barre d'état. Rien dessus, sauf
  le cube et « SceneView » en haut à gauche, et un bouton rond « i » en haut à droite
  (il ouvre About). Aucun menu à trois points, ici comme dans les démos.
  Ni titre, ni voile, ni dégradé.
- **Une feuille Material 3 : les 40 % du bas**, bord net, coins de 28 dp, poignée.
  Dedans :
  1. **Les six sections en onglets Material 3** : Models, AR, Scan, Materials, Play,
     Tools. La loupe de recherche est au bout de la rangée, au pouce.
  2. Une ligne discrète de vrais chiffres : « Live · 148k triangles · 6 lights ·
     60 fps ». Ils viennent du moteur, ils ne sont pas inventés. Un développeur comprend
     tout de suite que c'est du temps réel.
  3. **Les démos de la section**, en lignes avec une vignette. On glisse à gauche ou à
     droite pour changer de section ; on tire la feuille pour voir toute la liste.
- **Plus de barre de navigation en bas.** Voir [la logique de la démo](sceneview-demo-logique.md).
- **Plus de « trois choix », plus de « Try next », plus de carrousel.** Les six onglets
  les remplacent : une seule façon d'entrer, toujours au même endroit.

### 5.6 Clair et sombre

La scène suit le thème, et c'est une idée, pas une contrainte :
- **Clair : le matin.** Soleil net, travertin lumineux, ciel bleu pâle. La feuille claire
  se pose naturellement dessous.
- **Sombre : l'heure bleue.** Ciel bleu profond, premières étoiles, lampes allumées, le
  bassin reflète le pavillon éclairé.

Le téléphone qui bascule en sombre le soir fait donc tomber la nuit sur le pavillon. La
scène de nuit sur fond blanc de la 4.68, qui obligeait à un voile, n'existe plus.

**La première ligne de chaque section est la démo qui retient les gens** d'après les
chiffres d'usage : Model Viewer, Place in AR, 3D Scan, Cosmos. Matter, pas encore
publiée, est un pari. Sur 325 vraies
personnes en 28 jours (robot de test retiré), deux tiers ne viennent qu'une fois : tout
se joue dans la première minute, sur cet écran.

**Un tiers revient.** Pour lui, la feuille se rouvre sur l'onglet qu'il a quitté, avec
la caméra déjà à ce point de vue. L'ouverture « points vers lieu » reste, elle dure une
seconde et demie.

### 5.7 Pour qui

Pour un développeur qui se demande si SceneView vaut le coup, et pour Thomas. Les deux
veulent la même chose : voir du vrai temps réel, beau, qui répond. Aucun mot technique
n'est nécessaire pour l'apprécier ; la ligne de chiffres et « View source » sont là pour
celui qui cherche.

### 5.8 Ce que ce design ne garde pas

- Le circuit de nuit et sa voiture. Il reste comme démo dans « Models ».
- Les trois choix et « Try next » de #5005.
- L'onglet « AR View » et l'onglet « About » (détail dans la logique de la démo).
- Tout texte posé sur la scène.

Ce qu'il garde : les six sections, la règle « une démo reste dans sa section », la scène
composée une seule fois (pas de rechargement), les paliers de qualité pour les
téléphones modestes.

## 6. Dans quel ordre

1. **Ce soir** : le retour arrière vers l'accueil 4.68, s'il a été décidé. Il arrête les
   dégâts, rien de plus.
2. **Étape 1** : le pavillon, l'ouverture « points vers lieu », la vitre, le doigt, la
   feuille à six onglets, et les six positions de caméra. La vue Scan est vraie dès cette
   étape, puisque les points existent déjà pour l'ouverture. La vue AR aussi : c'est le
   deuxième onglet, celui que presque tout le monde verra.
3. **Étape 2** : les vraies vues Materials, Play et Tools, et l'onde sur le bassin.

Le détail est dans la [passation](sceneview-accueil-handoff.md).
