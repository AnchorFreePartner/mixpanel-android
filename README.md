# Latest Version [![](https://jitpack.io/v/AnchorFreePartner/mixpanel-android.svg)](https://jitpack.io/#AnchorFreePartner/mixpanel-android)

## Installation

### Dependencies in `build.gradle`

Add Mixpanel and Google Play Services to the `dependencies` section in *app/build.gradle*

```gradle
implementation "com.github.AnchorFreePartner:mixpanel-android:5.2.1.18"
implementation "com.google.android.gms:play-services:7.5.0+"
```

### Permissions in your `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Integration

### Initialization

```java
final MPConfig mpConfig = MPConfig.getInstance(context);
mpConfig.setEventsEndpoint("https://www.domain.com/my/event/endpoint.php");
mpConfig.setEventsFallbackEndpoints(getListOfBackupEndpoints());
final MixpanelAPI mixpanelTracker = MixpanelAPI.getInstance(context, BuildConfig.APPLICATION_ID);
```

### Super properties

These properties can be set once and will be reported with every event.
```java
final Map<String, Object> map = new HashMap<>();
map.put("epoch", System.currentTimeMillis());
map.put("app_name", BuildConfig.APPLICATION_ID);
map.put("build_flavor", BuildConfig.FLAVOR);
mixpanelTracker.registerSuperPropertiesMap(map);
```

### Tracking

```java
mixpanelTracker.track("app_start_event"); //Simple event with no additional properties

final JSONObject props = new JSONObject();
props.put("referrer", "Referrer Name");
props.put("Campaign", "Campaign Name");
mixpanelTracker.track("install_event", props);

final Map<String, Object> properties = new HashMap<>();
properties.put("button_name", "Purchase Button");
properties.put("sku", "my.product.sku");
mixpanelTracker.trackMap("click_event", properties);
```

# License

```
See LICENSE File for details. The Base64Coder,
ConfigurationChecker, and StackBlurManager classes, and the entirety of the
 com.mixpanel.android.java_websocket package used by this
software have been licensed from non-Mixpanel sources and modified
for use in the library. Please see the relevant source files, and the
LICENSE file in the com.mixpanel.android.java_websocket package for details.

The StackBlurManager class uses an algorithm by Mario Klingemann <mario@quansimondo.com>
You can learn more about the algorithm at
http://www.quasimondo.com/StackBlurForCanvas/StackBlurDemo.html.
```
