# ldap-identity-sync

Wrapper de `UserLocalService` (mas un `AttributesTransformer`) para Liferay DXP 2025.Q1
que resuelve, durante la sincronizacion LDAP -> Liferay, las colisiones entre el email
de un usuario de Active Directory y un usuario que ya existe en Liferay.

## El problema

Con la autenticacion por `screenName` (el numero de expediente), el import LDAP busca al
usuario por `screenName`. Hay tres situaciones:

1. **Externo pasa a interno.** Es la misma persona; cambia su expediente y mantiene el
   email. El import no la encuentra por `screenName`, intenta crearla y la creacion
   falla con `UserEmailAddressException.MustNotBeDuplicate`.
2. **Email reciclado para otra persona con otro expediente.** AD reasigna el email de
   alguien que se fue a una persona nueva. Misma excepcion que en el caso 1.
3. **Email Y expediente reciclados para otra persona.** El import encuentra al usuario
   antiguo por `screenName` y lo sobrescribe con los datos de la persona nueva, **sin
   ninguna excepcion**. La persona nueva hereda roles y contenido de la antigua.

## Que hace

Tres piezas:

| Pieza | Que hace |
|---|---|
| `LDAPUserIdentitySyncWrapper` | `UserLocalServiceWrapper`. En `addUser`: ante el duplicado de email (casos 1 y 2), borra el usuario en conflicto y repite el alta una vez. En `updateUser`: bloquea la actualizacion si el `objectGUID` entrante no coincide con el guardado (caso 3). |
| `ADObjectGuidAttributesTransformer` | `AttributesTransformer`. Convierte el `objectGUID` de AD (binario, 16 bytes) a texto y lo deja en el `ThreadLocal`. |
| `AdObjectGuidThreadLocal` | Pasa el `objectGUID` del transformador al wrapper dentro del mismo hilo de import. |

### Casos 1 y 2: borrar y reintentar (`addUser`)

Politica acordada con el cliente. Cuando el alta falla con `MustNotBeDuplicate` **y la
llamada procede del import LDAP** (ver "Seguridad"):

1. Busca al usuario con ese email.
2. No lo toca si no existe, si es el usuario guest o, por defecto, si es administrador.
3. En modo `dryRun` solo escribe en el log lo que borraria.
4. Si no, lo borra (`deleteUser`) y repite el `addUser` **una unica vez**.

El log indica si el `objectGUID` confirma que es la misma persona o una distinta; la
accion es la misma en los dos casos.

### Caso 3: bloquear la sobrescritura (`updateUser`)

`LDAPUserImporterImpl` llama en cada ciclo a un `updateUser` concreto, de muchos
parametros, para cada usuario existente. Si el usuario ya tiene un `objectGUID` guardado
(campo `adObjectGUID`) y el que llega ahora es distinto, el wrapper no aplica la
actualizacion, devuelve el usuario sin tocar y escribe un WARN para revision manual. El
`objectGUID` se guarda la primera vez que se ve a cada usuario y nunca se sobrescribe.

En cualquier `updateUser` que no venga del import LDAP (sin `objectGUID` entrante) no se
hace ninguna lectura extra.

## Mapeos y configuracion necesarios

**Sin esto el caso 3 no se detecta, y no hay ningun error que lo avise.** El import LDAP
solo pide a AD los atributos que estan mapeados en la configuracion del servidor
(`DefaultPortalLDAP.getUserAttributes` recoge los valores de los mapeos de usuario, de
contacto y de campos personalizados). Si `objectGUID` no esta mapeado en ninguna parte,
el transformador nunca lo ve.

### 1. Crear dos campos personalizados de User

Control Panel > Custom Fields > User > Add Custom Field. Tipo **Text**, con estos nombres:

| Campo | Para que sirve |
|---|---|
| `adObjectGUID` | Valor de referencia. Lo escribe **el wrapper**, una sola vez por usuario, y nunca se sobrescribe. |
| `adObjectGUIDMirror` | Solo existe para que el import pida `objectGUID` a AD. Lo rellena **el import LDAP** en cada ciclo; el wrapper no lo lee. |

### 2. Mapear `objectGUID` en el servidor LDAP

Instance Settings > Security > LDAP > Servers > (servidor) > Users > **Custom User
Mapping**, una linea:

```
adObjectGUIDMirror=objectGUID
```

### 3. Lo que NO hay que tocar

- **No mapear el campo "UUID" de User Mapping a `objectGUID`.** El import copia ese
  valor a `serviceContext.setUuid(...)` y `UserLocalServiceImpl.addUser` y `updateUser`
  lo usan para fijar el `uuid_` interno de cada usuario importado: cambiaria el `uuid_` de
  todos los usuarios existentes en el siguiente import. No se ha evaluado el impacto de
  ese cambio en otras partes de Liferay.
- **Dejar "Import User Sync Strategy" en `Auth Type`.** Con `UUID` el import trataria a
  los ~13.000 usuarios existentes como nuevos.

### Por que dos campos y no uno

Si se mapeara `adObjectGUID` directamente, el import lo rellenaria despues de cada
actualizacion, **tambien cuando el wrapper la ha bloqueado**. Escribiria el `objectGUID`
de la persona nueva encima del guardado, y en el siguiente ciclo ya coincidirian y el
bloqueo desapareceria. El campo espejo se puede sobrescribir sin consecuencias.

### 4. Antes de desplegar

Crear los dos campos (paso 1). Si `adObjectGUID` no existe, el wrapper avisa con un WARN
en cada intento y no guarda nada.

### Cobertura inicial

Los usuarios que ya existen no tienen `objectGUID` guardado: se les "siembra" en el
primer import tras el despliegue, con el valor que llegue en ese momento. El caso 3 solo
se detecta a partir del segundo ciclo. Si el reciclaje ya ocurrio antes del despliegue,
el valor sembrado sera el de la persona nueva y no se detectara.

## Por que en `UserLocalService`

Se evaluaron otros puntos y ninguno cubre todos los flujos:

- **`Authenticator` en `auth.pipeline.pre`.** `AuthPipeline` ejecuta los autenticadores
  uno tras otro; un try/catch en uno no envuelve a los siguientes. `LDAPAuth` captura la
  excepcion de su import dentro de un `try` cuyo `catch (Exception)` escribe `Problem
  accessing LDAP server` y devuelve `FAILURE`: el duplicado nunca sale. Ademas
  `AuthPipeline` solo se invoca desde el login con formulario; los SSO (SAML, OIDC,
  `AutoLogin`) no pasan por el.
- **Decorar `LDAPUserImporter`** (registrar un servicio con mayor ranking). Todos los
  consumidores lo llaman, pero `LDAPUserImporterImpl` hace su trabajo por usuario con
  llamadas `this`, que no pasan por el servicio registrado. Solo se veria el login con
  formulario. En el import programado cada usuario se importa dentro de un `try/catch`
  que solo registra `Unable to import user ...`.
- **Heredar de `LDAPUserImporterImpl`.** Esta en un paquete `internal` no exportado y
  sus dependencias son campos privados con `@Reference`.
- **`UserLocalService`** (este modulo). El importador LDAP, y SAML, lo llaman a traves
  del servicio inyectado, asi que un `UserLocalServiceWrapper` lo ve en todos los
  flujos: login, import programado, import al arrancar y SAML con import LDAP.

## Seguridad: el borrado solo actua en el import LDAP

`addUser` tambien lo usan el alta de cuenta del portal, la administracion de usuarios y
la API headless. Si el wrapper borrase al usuario con el email duplicado en cualquiera de
esas llamadas, bastaria registrarse con el email de otro para eliminar su cuenta.

Por eso solo borra si `serviceContext.getAttribute("ldapServerId") != null`.
`LDAPUserImporterImpl._importUser` pone ese atributo en el `ServiceContext` antes de
llamar a `addUser`. Es una marca interna, no un contrato publico: **hay que comprobarla
en cada upgrade de Liferay.**

## Configuracion opcional del wrapper

Archivo `com.client.ldap.identity.sync.LDAPUserIdentitySyncWrapper.config` en
`osgi/configs`:

```
dryRun=B"false"
protectAdministrators=B"true"
```

- `dryRun` (por defecto `false`): si es `true`, nunca borra; solo registra un WARN con el
  usuario que se borraria. **Recomendado para la primera prueba.**
- `protectAdministrators` (por defecto `true`): no borra usuarios con el rol
  Administrator.

## Limites

1. **Borrar cambia el `userId`.** Se pierden roles asignados a mano, contenido propio,
   preferencias y suscripciones. Si el `objectGUID` confirma que es la misma persona
   (caso 1) existe una alternativa que lo conserva: renombrar el `screenName` del usuario
   existente (`updateScreenName`) y devolverlo. Esta descrita paso a paso en un comentario
   dentro de `_deleteConflictingUser`, junto al `deleteUser`.
2. **El caso 3 solo se bloquea, no se resuelve.** El usuario nuevo no se sincroniza hasta
   que alguien lo revise. Queda por decidir la politica (por ejemplo, borrar el registro
   antiguo como en los casos 1 y 2, o notificar).
3. **Duplicado de `screenName`** (`UserScreenNameException.MustNotBeDuplicate`, por
   ejemplo un expediente reciclado en una persona nueva): no se trata.
4. **SAML sin import LDAP.** Si el proveedor SAML da de alta al usuario por su cuenta, la
   llamada a `addUser` no lleva la marca del import LDAP, y ni el borrado ni el guard del
   caso 3 (que depende del `objectGUID` del import) actuan.
5. **Si el login fuera por email** en vez de por `screenName`, el caso 2 deja de lanzar
   excepcion (el import encuentra al usuario por email) y solo lo cubriria el guard del
   caso 3.
6. **Transacciones.** Si `addUser` se ejecutara dentro de una transaccion externa, la
   excepcion podria dejarla marcada como `rollback-only` y el borrado y el reintento
   fallarian. No se ha comprobado.

## Supuestos que hay que validar

- Que el wrapper queda encadenado delante de `UserLocalService` (`scr:info` del
  componente: `ACTIVE`, referencia `SATISFIED`).
- Que el mapeo `adObjectGUIDMirror=objectGUID` hace que AD devuelva el atributo, y que
  llega como `byte[]` sin configuracion adicional. Si llegase como texto, el transformador
  escribe un WARN; en ese caso puede hacer falta pedirlo como `objectGUID;binary`.
- Que no hay otro `AttributesTransformer` registrado que entre en conflicto (el import
  inyecta una unica instancia; si hay mas, OSGi elige por `service.ranking`).
- Que el import LDAP procesa cada entrada de principio a fin en un unico hilo (el
  `ThreadLocal` depende de ello).
- Que el duplicado llega al wrapper con su tipo `UserEmailAddressException.MustNotBeDuplicate`
  (deducido de que `addUser` va fuera del `try/catch` interno de `_importUser`).
- Que la marca `ldapServerId` llega en el `ServiceContext` de `addUser` en cada flujo
  (login, cron, SAML con import LDAP).
- Que escribir el campo Expando `adObjectGUID` con `ExpandoBridge.setAttribute` persiste
  sin necesidad de otro `updateUser`.
- Que el login es por `screenName`.
- Que la firma del `updateUser` interceptado coincide con la de la build 2025.Q1 exacta.
- Que `release.dxp.api` expone todas las clases usadas. Si falla la compilacion, anadir la
  dependencia.

## Plan de pruebas sugerido

1. Crear los campos personalizados y el mapeo. Desplegar con `dryRun=B"true"`.
2. `scr:info` de los dos componentes: `ACTIVE`; referencia `UserLocalService` enlazada.
3. Forzar un import de un usuario existente. Comprobar que `adObjectGUID` se rellena con
   un GUID de formato `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` (y que `adObjectGUIDMirror`
   tambien).
4. Caso 1/2: en un LDAP de pruebas, crear en Liferay un usuario con el email de otro del
   directorio y otro `screenName`; forzar el import. Con `dryRun=true`: WARN
   `[dryRun] Se borraria ...` y el alta no se produce.
5. Repetir con `dryRun=false`: WARN de borrado, el alta se reintenta y funciona.
6. Repetir con el usuario en conflicto siendo administrador: no se borra.
7. Registrar una cuenta desde el portal con un email existente: debe fallar como siempre,
   sin borrar nada.
8. Caso 3: cambiar en el LDAP de pruebas el `objectGUID` de la persona mapeada a un
   `screenName` ya sembrado (o recrearla en AD con el mismo expediente); forzar el import:
   debe aparecer el WARN `Actualizacion LDAP bloqueada ...` y los datos no cambian.
9. Repetir el paso 5 por SAML con el import LDAP activado.

## Estructura

```
ldap-identity-sync/
  bnd.bnd
  build.gradle
  README.md
  src/main/java/com/client/ldap/identity/sync/internal/
    LDAPUserIdentitySyncWrapper.java
    ADObjectGuidAttributesTransformer.java
    AdObjectGuidThreadLocal.java
```
