package com.client.ldap.identity.sync.internal;

/**
 * Comparte, dentro del mismo hilo, el {@code objectGUID} (ya convertido a
 * texto) que {@link ADObjectGuidAttributesTransformer} extrae de la entrada
 * LDAP en curso, para que {@link LDAPUserIdentitySyncWrapper} lo pueda leer al
 * interceptar {@code addUser}/{@code updateUser} para ese mismo usuario.
 *
 * <p><b>Por que un ThreadLocal y no {@code serviceContext.getUuid()}.</b>
 * El import LDAP copia a {@code serviceContext.setUuid(...)} el atributo
 * mapeado en el campo "UUID" de User Mapping (se ha comprobado en
 * {@code DefaultLDAPToPortalConverter}: lo hace siempre que ese mapeo
 * exista, sea cual sea el "Import User Sync Strategy"). Se podria, pues, leer
 * ahi con {@code getUuidWithoutReset()}. Pero ese mismo valor lo usa Liferay en
 * {@code UserLocalServiceImpl.addUser} y {@code updateUser} para fijar el
 * {@code uuid_} interno de CADA usuario importado (mediante {@code getUuid()},
 * que ademas lo borra al leerlo). Mapear "UUID" a {@code objectGUID} para
 * poder leerlo aqui cambiaria el {@code uuid_} de todos los usuarios ya
 * existentes en el siguiente import. Para no tener ese efecto lateral, el
 * atributo se obtiene con un mapeo a un campo personalizado auxiliar (ver
 * README) y este valor viaja por un {@link ThreadLocal} interno a este
 * modulo.</p>
 *
 * <p>{@link ADObjectGuidAttributesTransformer} tiene acceso al
 * {@code objectGUID} (en el {@code Attributes} de la entrada LDAP), y se
 * ejecuta en el mismo hilo e inmediatamente antes de que el import LDAP llame
 * a {@code addUser}/{@code updateUser} para esa misma entrada.</p>
 *
 * <p>IMPORTANTE -- supuesto pendiente de validar (ver README, "Supuestos que
 * hay que validar"): que el import LDAP procesa cada entrada de principio a
 * fin (transformUser -> mapeo -> addUser/updateUser) de forma sincrona en un
 * unico hilo, sin intercalar el procesamiento de otra entrada en medio. Si
 * alguna vez no fuera asi, el valor leido aqui podria pertenecer a un usuario
 * distinto -- por eso {@link #getAndClear()} borra el valor nada mas leerlo,
 * para que un fallo de este supuesto sea visible (siguiente lectura da
 * {@code null}) en vez de arrastrar silenciosamente un valor viejo.</p>
 */
final class AdObjectGuidThreadLocal {

	/**
	 * Lee el valor guardado para el hilo actual y lo borra inmediatamente
	 * (para no arrastrarlo a la siguiente entrada LDAP procesada en este
	 * mismo hilo si esa siguiente entrada no tuviera {@code objectGUID}).
	 */
	static String getAndClear() {
		String adObjectGuid = _adObjectGuid.get();

		_adObjectGuid.remove();

		return adObjectGuid;
	}

	/**
	 * Guarda el {@code objectGUID} (ya convertido a texto) para el hilo
	 * actual. Llamar con {@code null} equivale a borrarlo -- se usa al
	 * empezar a procesar cada entrada LDAP, para que un fallo de conversion
	 * en la entrada actual no deje "colado" el valor de la entrada anterior.
	 */
	static void set(String adObjectGuid) {
		if (adObjectGuid == null) {
			_adObjectGuid.remove();
		}
		else {
			_adObjectGuid.set(adObjectGuid);
		}
	}

	private AdObjectGuidThreadLocal() {
	}

	private static final ThreadLocal<String> _adObjectGuid =
		new ThreadLocal<>();

}
