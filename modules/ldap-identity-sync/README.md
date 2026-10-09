# ldap-identity-sync

Wrapper de `UserLocalService` para Liferay DXP 2025.Q1 que resuelve, en la
sincronizacion LDAP -> Liferay, el alta que falla por **email duplicado**: el email del
usuario de Active Directory ya lo tiene otro usuario de Liferay con distinto `screenName`.

Hace una sola cosa: **antes de crear el usuario, si el email ya existe en otro usuario,
borra ese usuario**. Despues el alta sigue su curso normal.

## El problema

La empresa autentica por `screenName` (numero de expediente). El import LDAP busca al
usuario por `screenName` y, si no lo encuentra, intenta crearlo. Si el email ya lo usa otro
usuario, el alta falla con `UserEmailAddressException.MustNotBeDuplicate`.

| Caso | Situacion | Resultado hoy |
|------|-----------|---------------|
| 1 | Externo pasa a interno: nuevo expediente (screenName), mismo email | Excepcion, el usuario no se importa |
| 2 | Email reciclado para otra persona, con otro screenName | Idem |
| 3 | Email Y screenName reciclados para otra persona | Sin excepcion: el import encuentra al usuario antiguo por screenName y lo sobrescribe |

Este modulo resuelve los casos 1 y 2. El caso 3 **no** esta cubierto (ver "Limites").

## Que hace

Sobrescribe solo `UserLocalService.addUser` (la sobrecarga de 26 argumentos con un
`Locale`). Si la llamada procede del import LDAP:

1. Busca un usuario con ese email (`fetchUserByEmailAddress`).
2. Si no existe, no hace nada.
3. Si existe, lo descarta del borrado si es el usuario guest o, por defecto, si es
   administrador (se deja un WARN y el alta fallara como hoy).
4. En otro caso lo borra (`deleteUser`) y llama a `super.addUser`.

Cualquier otro metodo (`updateUser`, `deleteUser`, etc.) se delega sin cambios. Para
llamadas que no vienen del import LDAP el coste es leer un atributo del `ServiceContext`.

No hace falta comparar `screenName` y email: el importador solo llama a `addUser` cuando no
ha encontrado a nadie con ese `screenName`, asi que el usuario que ya tiene el email es,
por construccion, otro.

### Por que comprobar antes y no capturar la excepcion

Con una captura (`catch` de `MustNotBeDuplicate` alrededor de `super.addUser`) se depende de
que el fallo no deje marcada como fallida ninguna transaccion que impida el reintento.
Con la comprobacion previa la excepcion no llega a producirse, asi que el tema de las
transacciones no entra en juego.

## Por que en `UserLocalService`

| Punto de enganche | Veredicto |
|-------------------|-----------|
| `Authenticator` en `auth.pipeline.pre` | `AuthPipeline` solo se ejecuta en el login con formulario. No interviene en SAML/OIDC. Ademas `LDAPAuth` captura las excepciones del import (`Problem accessing LDAP server`). No ayuda en el import programado |
| Decorador de `LDAPUserImporter` | Solo ve las llamadas que entran por el servicio; el import programado y SAML pasan por `this` dentro del importador original |
| `UserLocalService` (este modulo) | Pasan todos los flujos: login con formulario, import programado, import al arrancar y SAML con "LDAP import" activado en el SP |

## Seguridad

`addUser` tambien lo usan el registro de cuenta, la administracion de usuarios y la API.
Borrar por email en esas llamadas seria peligroso, asi que solo se actua si el
`ServiceContext` lleva el atributo `ldapServerId`, que `LDAPUserImporterImpl` pone antes de
llamar a `addUser`. Ademas:

- Nunca se borra el usuario guest.
- Por defecto no se borra a un administrador (`protectAdministrators=true`).

## Configuracion (opcional)

Archivo `com.client.ldap.identity.sync.LDAPUserIdentitySyncWrapper.config` en
`osgi/configs`:

```
protectAdministrators=B"true"
```

- `protectAdministrators` (por defecto `true`): no borra usuarios con el rol Administrator.
  En el cliente ya se dio un borrado accidental de un usuario base de Liferay.

No hace falta ningun mapeo LDAP, campo personalizado ni cambio de configuracion adicional.

## Limites

1. **Caso 3 (email y screenName reciclados) no cubierto.** No hay excepcion; el import
   actualiza al usuario antiguo con los datos de la otra persona. Distinguirlo requiere un
   identificador estable de AD (`objectGUID`). Hubo una variante que lo hacia (campos
   personalizados, transformer de atributos y bloqueo en `updateUser`); esta en el commit
   `46b4e56` del repositorio y se retiro por complejidad y coste de lecturas a BBDD.
2. **Borrar cambia el `userId`.** Se pierden roles asignados a mano, contenido propio,
   preferencias y suscripciones. La alternativa que conserva el `userId`
   (`updateScreenName` sobre el usuario existente) esta descrita paso a paso en un
   comentario dentro de `_deleteConflictingUser`. Solo tiene sentido en el caso 1.
3. **Se borra antes de crear.** Si despues el alta falla por otro motivo (datos invalidos,
   `screenName` duplicado), el usuario antiguo ya estara borrado y el nuevo no se habra
   creado.
4. **SAML sin "LDAP import" no cubierto.** Si el SP crea los usuarios con el
   `UserProcessor` por defecto en vez de importarlos del LDAP, la llamada a `addUser` no
   lleva la marca `ldapServerId` y no se actua.
5. **No se tratan duplicados de `screenName`**
   (`UserScreenNameException.MustNotBeDuplicate`).
6. **La firma de `addUser`** puede cambiar entre versiones de Liferay; compilar contra la
   build 2025.Q1 concreta.

## Supuestos que hay que validar en un entorno real

- Que el `ServiceContext` que llega a `addUser` lleva `ldapServerId` en todos los flujos:
  login con formulario, import programado, import al arrancar y SAML con LDAP import.
- Que el borrado y el alta posterior funcionan cuando hay una transaccion exterior abierta
  (en el login con formulario no la hay: `authenticateByScreenName` es
  `Propagation.SUPPORTS`). Pendiente de comprobar en el import programado y SAML.
- Que `scr:info` muestra el componente `ACTIVE` y encadenado delante del servicio real.

## Plan de pruebas sugerido

1. Desplegar y comprobar con `scr:info com.client.ldap.identity.sync.internal.LDAPUserIdentitySyncWrapper`
   que esta `ACTIVE` y con la referencia `RoleLocalService` enlazada.
2. En un LDAP de pruebas, crear en Liferay un usuario con el email de un usuario del
   directorio y otro `screenName`; hacer login LDAP con el del directorio. Resultado
   esperado: WARN de borrado y el usuario nuevo creado.
3. Repetir lanzando el import programado, y despues con SAML + LDAP import.
4. Repetir con el usuario en conflicto siendo administrador: no se borra y el alta falla
   como hoy.
5. Comprobar que el alta de una cuenta desde el portal (sin LDAP) con un email repetido
   sigue dando el error habitual y no borra nada.
6. Comprobar que un usuario sin conflicto se crea y actualiza como siempre.

## Estructura

```
ldap-identity-sync/
  bnd.bnd
  build.gradle
  README.md
  src/main/java/com/client/ldap/identity/sync/internal/
    LDAPUserIdentitySyncWrapper.java
```
