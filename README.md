# Secretario

App de Android que lee las notificaciones de WhatsApp (y WhatsApp Business) en el propio móvil,
detecta los mensajes que hablan de un día o una hora («el jueves a las 5», «demà a les 10»,
«el 15 de octubre»…) y te avisa para añadirlos al calendario con un toque.

- **Nada sale del móvil.** La app no tiene permiso de Internet. Ni Chrome ni Google.
- Entiende **castellano y catalán**.
- Guarda en el calendario que elijas (por defecto, el que se llame «obsidian»), con aviso 15 min antes.
- Pantalla «Todos (14 días)»: todos los mensajes recibidos, por si alguno sin fecha clara era una cita.

Basado en el prototipo «secretario_android_prototype», reescrito.

## Instalar

1. Descarga el `.apk` (pestaña **Releases** del repositorio, o el archivo que te pase Claude) y ábrelo en el móvil.
   Android pedirá permiso para instalar apps de esa fuente: acéptalo.
2. Abre **Secretario** y ve resolviendo la lista de «Estado» de arriba abajo hasta que todo esté en ✅:
   acceso a notificaciones, avisos, calendario y batería sin restricciones.
3. Pulsa **Evento de prueba** y comprueba que aparece mañana a las 10:00 en Infomaniak. Después bórralo.
4. Pide a alguien que te mande un WhatsApp tipo «nos vemos el jueves a las 5» y mira que llega el aviso.

### Xiaomi (HyperOS)
Xiaomi cierra las apps en segundo plano. Además de la batería, en los ajustes de la app
Secretario activa el **inicio automático** (el nombre exacto del menú puede variar según la versión).
Si «Último WhatsApp captado» se queda parado mientras recibes mensajes, es eso.

## Limitaciones

- Solo ve lo que llega como **notificación**: chats silenciados o mensajes que lees con el chat abierto no pasan por aquí.
- «A las 5» sin más se toma como 17:00 y se marca con «(¿o por la mañana?)».
- Al «Añadir» crea el evento de 1 hora. Para cambiar algo, usa «Ajustar», que abre tu app de calendario con todo rellenado.

## Compilación

GitHub Actions compila en cada cambio (`.github/workflows/compilar.yml`): pasa las pruebas del lector de fechas
(`app/src/test`) y publica el APK en Releases. La firma (`firma/secretario.jks`) va en el repositorio para que
cada versión se pueda instalar encima de la anterior; por eso el repositorio debe seguir siendo **privado**.
