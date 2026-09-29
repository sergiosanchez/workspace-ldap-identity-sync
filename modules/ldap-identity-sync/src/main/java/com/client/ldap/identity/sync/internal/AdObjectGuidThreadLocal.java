package com.client.ldap.identity.sync.internal;

/**
 * Comparte, dentro del mismo hilo, el {@code objectGUID} (ya convertido a
 * texto) que {@link ADObjectGuidAttributesTransformer} extrae de la entrada
 * LDAP en curso, para que {@link LDAPUserIdentitySyncWrapper} lo pueda leer al
 * interceptar {@code addUser}/{@code updateUser} para ese mismo usuario.
 *
 * <p><b>Por que hace falta esto y no basta con {@code serviceContext.getUuid()}.</b>
 * La primera version de este componente asumia que el valor mapeado como
 * "UUID" en LDAP User Mapping llegaba automaticamente a
 * {@code serviceContext.getUuid()} en cualquier ciclo de import. Eso es falso:
 * el import LDAP solo traduce ese mapeo a {@code serviceContext.setUuid(...)}
 * cuando "Import User Sync Strategy" esta puesto a {@code UUID} -- con la
 * estrategia {@code Auth Type} (la que usa este componente a proposito, para
 * no depender de una migracion previa de todos los usuarios existentes),
 * {@code serviceContext.getUuid()} esta siempre a {@code null} durante el
 * import LDAP, pase lo que pase con el mapeo "UUID". Confirmado contra un
 * comentario real de un ingeniero de Liferay en LPS-67628 / LPSA-39880: "It is
 * obligatory to map the uuid in order to import those Users from LDAP while
 * using the 'ldap.import.user.sync.strategy=uuid' property" -- es decir, ese
 * mapeo solo importa bajo esa estrategia.</p>
 *
 * <p>Como {@link ADObjectGuidAttributesTransformer} SI tiene acceso al
 * {@code objectGUID} crudo (antes de cualquier mapeo, via el propio
 * {@code Attributes} de la entrada LDAP), y se ejecuta en el mismo hilo e
 * inmediatamente antes de que el import LDAP llame a
 * {@code addUser}/{@code updateUser} para esa misma entrada, un
 * {@link ThreadLocal} interno a este modulo es la forma mas simple de pasar
 * ese valor de un componente a otro sin depender de ningun mecanismo nativo
 * de Liferay que no hace lo que su nombre sugiere.</p>
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
