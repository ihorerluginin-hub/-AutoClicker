# Правила R8 выключены (isMinifyEnabled = false), но на случай включения:
# AccessibilityService обращается к себе по имени из манифеста, обфускация
# не должна переименовывать публичные классы и методы, читаемые рефлексией.
-keep class ru.autoclicker.service.ClickerAccessibilityService { *; }
-keep class ru.autoclicker.service.KeepAliveService { *; }
-keep class ru.autoclicker.service.BootReceiver { *; }
-keep class ru.autoclicker.AutoClickerApp { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
