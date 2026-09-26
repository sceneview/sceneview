/*
 * Teams named under "Found in public code and posts from teams at" on the home page and on
 * /companies.html. This array is the only place to edit: every <ul data-adoption-orgs>
 * on a page is filled from it.
 *
 * Rule for a name to appear here:
 *   - the evidence is the organization's OWN public material: its repository, its
 *     published library or app code, or its own engineering blog or talk;
 *   - that material shows SceneView (io.github.sceneview) or the maintained Sceneform
 *     line SceneView grew from (com.gorisse.thomas.sceneform / sceneview/sceneform-android);
 *   - `url` points at that evidence.
 * Not enough on its own: an issue, a question or a star from an employee, a personal
 * side project, a fork with no commits of its own. Text only, never a logo, and never
 * worded as "customers" or "used in production".
 *
 * Every entry was re-checked on 2026-09-26 (repository reachable, dependency still
 * declared on the default branch, or the post still online).
 */
(function () {
  var ORGS = [
    { name: 'Block', what: 'Bitkey app', url: 'https://github.com/proto-at-block/bitkey/blob/main/app/gradle/libs.versions.toml' },
    { name: 'Niantic Spatial', what: 'NSDK Kotlin samples', url: 'https://github.com/nianticspatial/nsdk-samples-kotlin/blob/main/NsdkSamples/NsdkSamples/build.gradle.kts' },
    { name: 'Safie', what: 'Safie Go 360, engineering blog', url: 'https://safie.hatenablog.com/entry/android-inverted-dome-camera' },
    { name: 'WillowTree', what: 'Vocable AAC app', url: 'https://github.com/willowtreeapps/vocable-android/blob/main/gradle/libs.versions.toml' },
    { name: 'Applied Recognition', what: 'Ver-ID Face Capture SDK', url: 'https://github.com/AppliedRecognition/Face-Capture-Android/blob/main/gradle/libs.versions.toml' },
    { name: 'Metabind', what: 'BindJS for Android', url: 'https://github.com/metabindai/bindjs-android/blob/main/gradle/libs.versions.toml' },
    { name: 'OpenOrigins', what: 'React Native camera SDK', url: 'https://github.com/OpenOrigins/react-native-source-camera/blob/main/android/build.gradle' },
    { name: 'Bird', what: 'SceneView fork', url: 'https://github.com/birdrides/sceneview-android/commits/main' },
    { name: 'Squint', what: 'maintained Sceneform fork', url: 'https://github.com/SquintInc/sceneform-android/commits/master' },
    { name: 'Grand Valley State University', what: 'Art at GVSU app', url: 'https://github.com/gvsucis/art-at-gvsu-android/blob/main/app/build.gradle.kts' }
  ];

  var lists = document.querySelectorAll('[data-adoption-orgs]');
  Array.prototype.forEach.call(lists, function (list) {
    ORGS.forEach(function (org) {
      var item = document.createElement('li');
      item.className = 'adoption-org';
      var link = document.createElement('a');
      link.className = 'adoption-org__link';
      link.href = org.url;
      link.target = '_blank';
      link.rel = 'noopener';
      link.setAttribute('aria-label', org.name + ' (' + org.what + '): public evidence, opens in a new tab');
      var name = document.createElement('span');
      name.className = 'adoption-org__name';
      name.textContent = org.name;
      var what = document.createElement('span');
      what.className = 'adoption-org__what';
      what.textContent = org.what;
      link.appendChild(name);
      link.appendChild(what);
      item.appendChild(link);
      list.appendChild(item);
    });
  });
})();
