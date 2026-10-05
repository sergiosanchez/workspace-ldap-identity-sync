package com.client.ldap.identity.sync.internal;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.security.ldap.AttributesTransformer;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;

import org.osgi.service.component.annotations.Component;

/**
 * Convierte el atributo {@code objectGUID} de Active Directory (binario, 16
 * bytes) a su representacion en texto estandar antes de que el resto del
 * import LDAP lo procese.
 *
 * <p><b>Por que hace falta este componente.</b> El extractor generico de
 * Liferay para convertir un atributo LDAP a texto ({@code LDAPUtil}) hace,
 * en esencia, esto:
 *
 * <pre>{@code
 * Object object = attribute.get();
 * return object.toString();
 * }</pre>
 *
 * Para la mayoria de atributos (texto), eso funciona. Pero {@code objectGUID}
 * en Active Directory tiene sintaxis binaria (Octet String): el proveedor
 * JNDI de LDAP lo entrega como {@code byte[]}, no como texto. Llamar a
 * {@code toString()} sobre un {@code byte[]} en Java NO da el GUID -- da
 * algo como {@code [B@6d06d69c} (la representacion por defecto de un array),
 * que ademas cambia en cada lectura porque cada vez es una instancia de
 * array distinta.</p>
 *
 * <p>Este componente usa el punto de extension oficial de Liferay para este
 * tipo de casos ({@code AttributesTransformer}, documentado en Liferay
 * Learn), que se ejecuta ANTES de que el import LDAP mapee ningun atributo.
 * Convierte el valor binario de {@code objectGUID} a su representacion en
 * texto estandar (formato {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}),
 * aplicando el orden de bytes mixto que usa el formato GUID de Microsoft (los
 * primeros 3 grupos en little-endian, los ultimos 8 bytes tal cual), y deja
 * ese texto en {@link AdObjectGuidThreadLocal} para que
 * {@link LDAPUserIdentitySyncWrapper} lo recoja al interceptar
 * {@code addUser}/{@code updateUser} para esa misma entrada -- ver el javadoc
 * de {@link AdObjectGuidThreadLocal} para el porque de este mecanismo.</p>
 *
 * <p><b>Precondicion.</b> El import LDAP solo pide a AD los atributos que
 * estan mapeados en la configuracion del servidor LDAP (se ha comprobado en
 * {@code DefaultPortalLDAP.getUserAttributes}: recoge los valores de los
 * mapeos de usuario, contacto y campos personalizados). Si {@code objectGUID}
 * no esta mapeado en ningun sitio, este componente no lo vera nunca y el
 * mecanismo completo queda inerte, sin ningun error. Ver README, "Mapeos y
 * configuracion necesarios".</p>
 *
 * <p>IMPORTANTE -- pendiente de validar contra vuestra build 2025.Q1 (ver
 * README, "Supuestos que hay que validar"):</p>
 * <ul>
 *   <li>Que el proveedor JNDI entrega {@code objectGUID} como {@code byte[]}
 *       sin configuracion adicional. Si en vuestro entorno llega ya como
 *       texto (aunque sea un texto "raro"), este componente lo detecta
 *       ({@code value instanceof byte[]}) y no lo toca, dejando un WARN en
 *       el log -- revisar ese WARN si aparece, porque probablemente haga
 *       falta pedir el atributo explicitamente como
 *       {@code objectGUID;binary} en la busqueda LDAP en vez de solo
 *       {@code objectGUID}.</li>
 *   <li>Que no hay ya otro {@code AttributesTransformer} registrado en
 *       vuestro entorno que entre en conflicto con este (el import LDAP
 *       inyecta una unica instancia de {@code AttributesTransformer}; si
 *       hay mas de una registrada, OSGi elige una por ranking de servicio).
 *       Si hace falta, subir la prioridad de este componente con la
 *       propiedad {@code service.ranking}.</li>
 * </ul>
 */
@Component(immediate = true, service = AttributesTransformer.class)
public class ADObjectGuidAttributesTransformer implements AttributesTransformer {

	@Override
	public Attributes transformGroup(Attributes attributes) {
		return attributes;
	}

	@Override
	public Attributes transformUser(Attributes attributes) {
		return _convertObjectGuidToString(attributes);
	}

	private Attributes _convertObjectGuidToString(Attributes attributes) {

		// Se borra primero, incondicionalmente: si esta entrada LDAP no tiene
		// objectGUID (o falla la conversion), no debe quedar "colado" el
		// valor de la entrada anterior procesada en este mismo hilo. Ver
		// javadoc de AdObjectGuidThreadLocal.
		AdObjectGuidThreadLocal.set(null);

		Attribute objectGuidAttribute = attributes.get(
			_LDAP_ATTRIBUTE_OBJECT_GUID);

		if (objectGuidAttribute == null) {
			return attributes;
		}

		try {
			Object value = objectGuidAttribute.get();

			if (value == null) {
				return attributes;
			}

			if (!(value instanceof byte[])) {

				// El proveedor JNDI no esta entregando este atributo como
				// binario -- ver la nota en el javadoc de esta clase y el
				// README.
				_log.warn(
					"El atributo LDAP '" + _LDAP_ATTRIBUTE_OBJECT_GUID +
						"' no ha llegado como byte[] (era " +
							value.getClass().getName() + "). Revisar la " +
								"seccion sobre objectGUID en el README -- " +
									"puede que haga falta pedirlo " +
										"explicitamente como '" +
											_LDAP_ATTRIBUTE_OBJECT_GUID +
												";binary' en la busqueda " +
													"LDAP.");

				return attributes;
			}

			String objectGuidString = _toCanonicalGuidString((byte[])value);

			Attribute convertedAttribute = new BasicAttribute(
				_LDAP_ATTRIBUTE_OBJECT_GUID);

			convertedAttribute.add(objectGuidString);

			attributes.remove(_LDAP_ATTRIBUTE_OBJECT_GUID);
			attributes.put(convertedAttribute);

			AdObjectGuidThreadLocal.set(objectGuidString);
		}
		catch (Exception e) {
			_log.warn(
				"No se ha podido convertir el atributo '" +
					_LDAP_ATTRIBUTE_OBJECT_GUID + "' a texto", e);
		}

		return attributes;
	}

	/**
	 * Active Directory guarda {@code objectGUID} como 16 bytes con el orden
	 * de bytes mixto propio del formato GUID de Microsoft (COM): los
	 * primeros 3 grupos (4+2+2 bytes) en little-endian, y los ultimos 8
	 * bytes tal cual (big-endian). Sin esta conversion, leer los bytes en
	 * orden directo da un identificador distinto al que se ve, por ejemplo,
	 * en ADSI Edit o PowerShell.
	 */
	private String _toCanonicalGuidString(byte[] guidBytes) {
		StringBuilder sb = new StringBuilder(36);

		_appendHex(sb, guidBytes[3]);
		_appendHex(sb, guidBytes[2]);
		_appendHex(sb, guidBytes[1]);
		_appendHex(sb, guidBytes[0]);
		sb.append('-');
		_appendHex(sb, guidBytes[5]);
		_appendHex(sb, guidBytes[4]);
		sb.append('-');
		_appendHex(sb, guidBytes[7]);
		_appendHex(sb, guidBytes[6]);
		sb.append('-');
		_appendHex(sb, guidBytes[8]);
		_appendHex(sb, guidBytes[9]);
		sb.append('-');

		for (int i = 10; i <= 15; i++) {
			_appendHex(sb, guidBytes[i]);
		}

		return sb.toString();
	}

	private void _appendHex(StringBuilder sb, byte b) {
		String hex = Integer.toHexString(b & 0xFF);

		if (hex.length() == 1) {
			sb.append('0');
		}

		sb.append(hex);
	}

	private static final String _LDAP_ATTRIBUTE_OBJECT_GUID = "objectGUID";

	private static final Log _log = LogFactoryUtil.getLog(
		ADObjectGuidAttributesTransformer.class);

}
