# ldap-identity-sync

Componente OSGi de sincronizacion LDAP -> Liferay DXP 2025.Q1 que corrige dos escenarios
de colision de identidad durante el import LDAP, sin depender del "Import User Sync
Strategy: UUID" nativo ni de una migracion previa de los usuarios existentes.

## El problema

1. **Externo pasa a interno.** El "numero de expediente" (mapeado a `screenName`)
   cambia al pasar de externo a interno (p.ej. de `E12345` a `U67890`). El import LDAP
   no reconoce al usuario por su criterio habitual (screenName o email) e intenta crear
   un usuario nuevo. La creacion falla con `UserEmailAddressException.MustNotBeDuplicate`
   porque el email corporativo de esa persona ya existe en su registro antiguo.
2. **Email reciclado para otra persona.** Active Directory reutiliza un email (y, si el
   screenName se genera a partir del nombre, a veces tambien el screenName) para un
   empleado distinto. Aqui no hay excepcion: el import LDAP encuentra "correctamente"
   al usuario antiguo por su criterio de matching y lo actualiza con los datos de la
   persona equivocada.

## Como lo resuelve este componente

La idea es sencilla, en tres pasos:

1. Cada vez que sincroniza, LDAP trae el identificador unico de la persona: el
   `objectGUID` de Active Directory. Este componente lo recoge internamente (ver seccion
   "Como se comparte el objectGUID entre los dos componentes") -- no hace falta ninguna
   configuracion de LDAP User Mapping para esto.
2. El componente guarda ese identificador en un campo propio del usuario, `adObjectGUID`
   (lo unico que crea y usa este componente para todo esto).
3. En cada sincronizacion siguiente, compara el `objectGUID` que llega con el que tiene
   guardado en `adObjectGUID` para ese usuario.

Con eso resuelve los dos escenarios:

- **Escenario 1:** cuando la creacion falla por duplicado, busca un usuario existente
  con ese email. Si su `adObjectGUID` guardado coincide (o todavia no tiene ninguno) y el
  nombre/apellidos son plausibles, actualiza ese registro (nuevo screenName incluido) en
  vez de propagar el error.
- **Escenario 2:** antes de aplicar una actualizacion, compara el `objectGUID` entrante
  con el `adObjectGUID` guardado del usuario. Si no coincide, bloquea la actualizacion
  automatica y deja un WARN en el log en vez de sobrescribir los datos de una persona
  con los de otra.
- `adObjectGUID` se guarda ("siembra") la primera vez que hay una coincidencia de
  confianza -- no hace falta ningun backfill previo sobre los usuarios existentes.

`adObjectGUID` es el unico dato que este componente lee y compara para tomar decisiones.
No usa ni toca ningun otro campo de identidad de `User`.

## Una pieza mas: convertir `objectGUID` de binario a texto

Este repositorio incluye un segundo componente, `ADObjectGuidAttributesTransformer`, que
hace falta desplegar junto con el wrapper -- no es opcional.

El motivo es concreto: en Active Directory, `objectGUID` no es texto, es un valor binario
de 16 bytes. Si se lee tal cual (sin conversion), Java no obtiene el GUID legible, sino
algo como `[B@6d06d69c` -- la representacion por defecto de un array en Java --, que
ademas es distinta en cada lectura, incluso para el mismo usuario. Si eso llegase sin
convertir a `adObjectGUID`, el escenario 2 compararia dos valores sin sentido, nunca
coincidirian, y el wrapper bloquearia **todas** las actualizaciones de usuarios desde el
primer dia -- no solo los reciclajes de email reales. Es decir: sin este componente
adicional, el mecanismo completo no funciona, no es un matiz menor.

`ADObjectGuidAttributesTransformer` resuelve esto usando el punto de extension oficial
de Liferay para este caso (`AttributesTransformer`, el mismo que usa el propio
importador LDAP para pre-procesar los atributos antes de mapear nada). Convierte el
valor binario de `objectGUID` a su formato de texto estandar
(`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) antes de que el resto del import LDAP lo toque.

Ver el javadoc de la clase para el detalle de la conversion y dos supuestos concretos
que hay que confirmar en vuestro entorno (ver tambien "Supuestos que hay que validar").

## Como se comparte el `objectGUID` entre los dos componentes

Esto merece su propia seccion porque una version anterior de este documento decia algo
incorrecto: que bastaba con mapear un campo `UUID` en LDAP User Mapping a `objectGUID`
para que el valor llegase solo hasta el wrapper via `serviceContext.getUuid()`. Eso es
falso, y conviene explicar por que para no repetir el error.

**Ese mapeo de "UUID" en Instance Settings solo lo usa el import LDAP cuando "Import User
Sync Strategy" esta puesto a `UUID`** (la estrategia alternativa a `Auth Type`, que sirve
para que Liferay busque al usuario existente por su `uuid_` interno en vez de por
screenName/email). Con `Auth Type` -- la que usa este componente, a proposito, para no
depender de una migracion previa de todos los usuarios existentes -- ese mapeo no hace
nada: el import LDAP nunca traduce ese campo a `serviceContext.setUuid(...)`, pase lo que
pase con la configuracion de User Mapping. Cita textual de un ingeniero de Liferay en el
ticket LPS-67628 / LPSA-39880, sobre esta misma relacion entre el mapeo "UUID" y la
estrategia UUID: *"It is obligatory to map the uuid in order to import those Users from
LDAP while using the 'ldap.import.user.sync.strategy=uuid' property"* -- es decir, ese
mapeo solo importa bajo esa estrategia.

**Por eso NO hay que tocar nada en Instance Settings para esto.** En su lugar, el
`objectGUID` viaja de un componente a otro dentro del propio modulo:

1. `ADObjectGuidAttributesTransformer` lee el `objectGUID` binario directamente del
   `Attributes` de la entrada LDAP en curso (antes de que se mapee nada) y, tras
   convertirlo a texto, lo deja en un `ThreadLocal` interno (`AdObjectGuidThreadLocal`).
2. `LDAPUserIdentitySyncWrapper` lo recoge de ahi (y lo borra al leerlo) al interceptar
   `addUser`/`updateUser` para esa misma entrada.

Esto SI depende de un supuesto que hay que validar (ver "Supuestos que hay que validar"):
que el import LDAP procesa cada entrada de principio a fin -- transformacion, mapeo,
`addUser`/`updateUser` -- de forma sincrona en un unico hilo, sin intercalar el
procesamiento de otra entrada por medio. Es el comportamiento normal y esperable de un
import secuencial, pero conviene confirmarlo con un log de prueba antes de dar esto por
definitivo.

**Nota aparte, no relacionada con lo anterior:** dejad "Import User Sync Strategy" en
`Auth Type` tal cual esta hoy. Cambiarlo a `UUID` es un cambio de comportamiento mucho
mayor de lo que parece -- pasa a buscar a TODOS los usuarios existentes por su `uuid_`
interno en vez de por screenName/email, y ninguno de los ~13.000 usuarios actuales tiene
ese `uuid_` sincronizado con su `objectGUID` de AD, así que el primer ciclo los trataria
a todos como nuevos. Este componente evita precisamente depender de eso.

## Prerrequisitos de configuracion

1. **Instance Settings > Custom Fields**, entidad `User`: crear un campo de tipo texto
   llamado `adObjectGUID` (o el nombre que prefirais, cambiando la constante
   `EXPANDO_COLUMN_AD_OBJECT_GUID` en el codigo). Si no existe al desplegar, el
   componente no falla: detecta su ausencia y deja un WARN en el log en vez de guardar
   nada.
2. Desplegar tambien `ADObjectGuidAttributesTransformer` (ver seccion anterior) --
   sin el, el wrapper nunca recibe un `objectGUID` utilizable.
3. No tocar "Import User Sync Strategy" (dejarlo en `Auth Type`) ni el mapeo "UUID" de
   LDAP User Mapping -- ver seccion anterior, no hace falta para nada de esto.

## Overloads de `UserLocalService` que intercepta

El importador LDAP (`LDAPUserImporterImpl`) no llama a los overloads mas simples de
`UserLocalService`, sino a dos overloads especificos de muchos parametros posicionales.
Este componente sobreescribe exactamente esos dos (no `updateUser(User user)`, que el
importador LDAP nunca invoca):

- `addUser(long creatorUserId, ..., ServiceContext serviceContext)`
- `updateUser(long userId, ..., ServiceContext serviceContext)` -- el overload
  "clasico" que termina con los grupos/roles y el `ServiceContext`.

El `objectGUID` entrante NO sale de ese `ServiceContext` (ver seccion anterior, "Como se
comparte el objectGUID entre los dos componentes") -- sale del `ThreadLocal` que deja
`ADObjectGuidAttributesTransformer`. La firma exacta de estos overloads (numero y orden
de parametros, sobre todo los campos de redes sociales del `updateUser`) puede variar
levemente segun la version de Liferay -- confirmadla contra el javadoc de
`UserLocalService` de vuestra build 2025.Q1 antes de compilar (ver "Supuestos a
validar").

## Nota sobre el registro OSGi del componente

Al ser un `ServiceWrapper`, el componente debe registrarse como
`@Component(service = ServiceWrapper.class)` (no como
`@Component(service = UserLocalService.class)`) y usar un metodo `@Reference` que
llame a `setWrappedService(...)` con el `UserLocalService` real inyectado. Sin esto,
Liferay lo desplegaria y activaria sin ningun error, pero el componente no
interceptaria ninguna llamada -- se quedaria inerte sin que nada en los logs lo
delatase. Es el patron oficial documentado por Liferay ("Creating Service Wrappers").

## Supuestos que hay que validar antes de produccion

Este codigo es un punto de partida razonado, no un artefacto verificado contra vuestra
build exacta de 2025.Q1. Antes de desplegarlo:

1. **Firmas exactas de `addUser(...)` y `updateUser(...)` usadas por el importador
   LDAP.** Ya verificadas contra el codigo fuente real de `UserLocalService` (rama
   `master` publica de `liferay-portal`) y corregidas -- el `addUser` sobreescrito recibe
   un unico `Locale` (no `languageId`/`timeZoneId` sueltos) y un parametro `type` antes de
   los IDs de grupo, sin `facebookId`/`openId`. Aun asi, confirmad la firma contra el
   javadoc de vuestra build 2025.Q1 exacta antes de dar esto por definitivo: la rama
   publica no tiene por que coincidir byte a byte con vuestro build interno.
2. **Que el `ThreadLocal` entre los dos componentes lleva el valor correcto.** Ver "Como
   se comparte el objectGUID entre los dos componentes" mas arriba. Asume que el import
   LDAP procesa cada entrada de forma sincrona y secuencial en un unico hilo
   (transformacion -> mapeo -> `addUser`/`updateUser`). Antes de confiar en esto,
   confirmadlo con un log de prueba: registrad el `objectGUID` leido en
   `ADObjectGuidAttributesTransformer` junto con algun dato identificativo de la entrada
   (screenName o email), y el mismo dato al leerlo en `LDAPUserIdentitySyncWrapper` --
   deben coincidir siempre, entrada a entrada.
3. **Conversion de `objectGUID` (binario) a texto.** Resuelta por
   `ADObjectGuidAttributesTransformer` (ver seccion dedicada mas arriba), pero quedan dos
   cosas por confirmar en vuestro entorno real de AD, no en el LDAP de test:
   - Que `objectGUID` llega como `byte[]` sin configuracion adicional (si no, el
     componente lo detecta y avisa en el log -- ver su javadoc para el siguiente paso,
     pedirlo como `objectGUID;binary`).
   - Que no hay ya otro `AttributesTransformer` registrado en vuestro entorno que
     entre en conflicto con este (el import LDAP usa una unica instancia; con mas de
     una registrada, OSGi elige por ranking de servicio -- subir la prioridad de este
     componente con `service.ranking` si hiciera falta).
4. **Persistencia de `ExpandoBridge.setAttribute(...)`.** Debe escribir directamente en
   la tabla de valores Expando sin necesitar una llamada adicional a `updateUser`. Es el
   comportamiento estandar de Liferay, pero conviene confirmarlo en vuestro entorno de
   test antes de confiar en el en produccion.
5. **Politica del escenario 2.** Hoy el codigo solo bloquea y registra un WARN. Falta
   decidir con el cliente que pasa despues: revision manual en una cola, liberar
   automaticamente el email del usuario antiguo (renombrandolo) para que el nuevo se
   cree limpio, u otra politica. El punto de extension esta marcado con `TODO(cliente)`
   en el codigo.
6. **Coincidencia por nombre y apellidos como salvaguarda del escenario 1.** Es una
   heuristica, no una garantia. El screenName (numero de expediente) NO sirve como
   identificador estable aqui, porque es precisamente el valor que cambia en este
   escenario. Si teneis otro atributo de negocio estable entre externo e interno (DNI,
   un ID de empleado separado del expediente...), usadlo en su lugar.
7. **Placeholder para reciclaje de numero de expediente.** Hay un `TODO(cliente)` en
   `_resolveCreateConflict` para el escenario hermano del 2 pero con el screenName: que
   un expediente antiguo (ya liberado al pasar su titular a interno) se reutilice para
   dar de alta a un externo distinto. Hoy no esta implementado -- solo esta marcado el
   sitio y el enfoque a seguir si en algun momento se confirma que hace falta.

## Plan de pruebas sugerido

1. Desplegar en un entorno de test con un subconjunto de usuarios de prueba, tras crear
   el campo Expando `adObjectGUID` en `User`. Idealmente contra un AD real o una copia
   fiel, no solo contra un LDAP de test generico -- es la unica forma de validar de
   verdad la conversion de `objectGUID`.
2. Confirmar, con un log de prueba, que `updateUser(...)` se ejecuta durante un ciclo de
   import normal sobre un usuario ya existente, y que el valor guardado en
   `adObjectGUID` es un GUID legible (formato
   `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`), no algo tipo `[B@...`.
3. Reproducir el escenario 1: cambiar el screenName de un usuario de prueba en el LDAP
   de test (simulando externo -> interno) y forzar un ciclo de import. Confirmar que se
   actualiza el usuario existente en vez de crear uno duplicado, y que queda el
   `objectGUID` guardado en el campo Expando.
4. Reproducir el escenario 2: crear una entrada LDAP nueva que reutilice el email de un
   usuario de prueba que ya tenga `objectGUID` guardado, con nombre/apellidos distintos.
   Confirmar que la actualizacion se bloquea, aparece el WARN en el log, y no se
   sobrescribe el registro original.
5. Confirmar que un ciclo de import normal, sin conflictos, sigue funcionando igual que
   hoy (el wrapper no debe introducir ningun cambio de comportamiento cuando no hay
   colision).
6. Solo despues de validar 1-5 en test, planificar el despliegue a produccion.

## Estructura del modulo

Este repositorio ya esta organizado como un modulo OSGi de Liferay Workspace, listo para
copiarse dentro de `modules/` de vuestro workspace (Target Platform, `dxp-2025.q1.*`):

```
ldap-identity-sync/
├── bnd.bnd
├── build.gradle
└── src/main/java/com/client/ldap/identity/sync/internal/
    ├── LDAPUserIdentitySyncWrapper.java
    ├── ADObjectGuidAttributesTransformer.java
    └── AdObjectGuidThreadLocal.java
```

`bnd.bnd`:

```
Bundle-Name: ldap-identity-sync
Bundle-SymbolicName: com.client.ldap.identity.sync
Bundle-Version: 1.0.0
```

`build.gradle`:

```gradle
dependencies {
	compileOnly group: "com.liferay.portal", name: "release.dxp.api"
}
```

No hace falta fijar versiones individuales de `portal-kernel`, `osgi.cmpn` ni del API de
Expando: al ser un modulo dentro de un Liferay Workspace, la resolucion de dependencias por
Target Platform (`release.dxp.api`) ya da acceso a todo el API de portal, incluido
`ExpandoBridge` y las anotaciones de Declarative Services, en la version exacta que
corresponde a vuestro `liferay.workspace.product`.

Antes de desplegarlo, compilad el modulo desde vuestro entorno (por ejemplo
`./gradlew :modules:ldap-identity-sync:compileJava`, o `blade gw clean build`) para
confirmar que resuelve sin problemas contra vuestra build real de 2025.Q1.

Un primer intento de compilacion (contra `release.dxp.api`, resuelto correctamente sin
problemas de dependencias) saco a la luz varios detalles que ya estan corregidos en el
codigo de este repositorio, verificados contra el codigo fuente publico de
`liferay-portal`:

- `AttributesTransformer` vive en el paquete `com.liferay.portal.kernel.security.ldap`,
  no en `com.liferay.portal.security.ldap`.
- Las excepciones de duplicado ya no existen como clases independientes
  (`DuplicateUserEmailAddressException` / `DuplicateUserScreenNameException`): son clases
  anidadas, `UserEmailAddressException.MustNotBeDuplicate` y
  `UserScreenNameException.MustNotBeDuplicate`.
- El overload de `addUser(...)` que expone `UserLocalService` recibe un unico `Locale`
  (no `languageId`/`timeZoneId` sueltos) y un parametro `type` antes de los IDs de grupo,
  sin `facebookId` ni `openId` -- la firma sobreescrita en el codigo ya se ha ajustado.
- `Validator.equals(Object, Object)` ya no existe en `portal-kernel`; el codigo usa
  `java.util.Objects.equals(...)` en su lugar.

Aun con esto corregido, no ha sido posible verificar la compilacion completa desde este
entorno (sin salida de red al repositorio de Gradle), asi que conviene que volvais a
lanzar la build vosotros y confirmeis que compila limpio contra vuestra build 2025.Q1
exacta antes de desplegar.

## Apendice: por que un campo nuevo y no uno ya existente

Esta seccion es solo para quien quiera el detalle -- no hace falta leerla para entender
ni desplegar el componente (ver seccion "Como lo resuelve" mas arriba, que es
autosuficiente).

`User` ya tiene dos campos que a primera vista podrian parecer utiles para esto, y
ambos se descartaron:

- **El campo interno `uuid_`** (el que gestiona el desplegable "Import User Sync
  Strategy: UUID"): todo usuario ya lo tiene relleno desde que se crea -- si nadie
  especifica uno, Liferay le asigna uno propio --, y el importador LDAP nunca lo
  actualiza para un usuario ya existente. Para los usuarios ya existentes tendria un
  valor sin relacion con LDAP, y compararlo bloquearia como "posible reciclaje de email"
  a todo el mundo desde el primer dia. Este componente no lee ni escribe `uuid_` en
  ningun momento.
- **`User.externalReferenceCode`:** tecnicamente disponible, pero es un campo con un
  papel propio dentro del framework de Objects de Liferay (clave de upsert de las APIs
  REST de Objects, extendido tambien a System Objects como `User`). Usarlo aqui
  mezclaria dos cosas sin relacion.

Un campo Expando nuevo, en cambio, empieza realmente vacio para todos los usuarios
existentes -- lo que permite ir "sembrando" el identificador progresivamente, sin
backfill -- y es el mecanismo estandar de Liferay para guardar un dato adicional "solo
para nosotros" sin heredar ninguna semantica ajena.
