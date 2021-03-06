/*
Copyright 2012, Jernej Kovacic

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/ 

package com.jkovacic.ssh2;

import java.util.*;


/**
 * Java cryptography providers provide ASN.1 encoded DSA digital signatures as evident from: 
 * http://docs.oracle.com/cd/E18355_01/security.1013/b25378/oracle/security/crypto/core/DSA.html
 * and several other sources (e.g. https://forums.oracle.com/forums/thread.jspa?threadID=2147350)
 * On the other hand, SSH public key authentication protocol requires IEEE P1363 
 * encoded DSA digital signatures. The IEEE P1363 standard is not available free of charge, 
 * fortunately it is described in this article:
 * http://www.codeproject.com/Articles/25590/Cryptographic-Interoperability-Digital-Signatures
 * 
 * This class converts ASN.1 encoded DSA digital signatures (returned by Signer) into
 * a SSH compliant format (IEEE P1363). The class is stateful and requires the following procedure
 * to be performed:
 * - instantiate the class using its constructor and pass it a DER encoded DSA signature
 * - call convert() to convert from ASN.1 to the SSH compliant DSA signature
 * - success of the conversion can be checked by calling ready()
 * - the converted signature may be obtain via getSshSignature() (or null will be returned if not available)
 * 
 * Note: For DSA signatures, SSH standard (RFC 4253) strictly requires 40-byte signature
 * blobs. Typically this is a case for 1024-bit DSA keys. If a longer DSA key pair is properly 
 * generated, its subprime (Q) would be longer, resulting in longer signatures which is not
 * supported by the SSH specifications. However, some key generators (e.g. Putty or OpenSSH) can generate
 * longer DSA keys with shorter Q that can generate short enough signatures to successfully perform authentication.
 * In other words, it is possible to use "adapted" DSA keys longer than 1024-bit, however such keys are no
 * stronger than 1024-bit keys. More info about this at:
 * https://fogbugz.bitvise.com/default.asp?Tunnelier.2.5037.1
 * 
 * This class does accept too long signatures (generated by keys whose Q is too long), however the
 * adapted signature will be "shortened" to 40 bytes and as such impossible to verify.
 * 
 * @author Jernej Kovacic
 */

public class DsaSignatureAdapter extends DigitalSignatureAdapter
{
	// Length (in bytes) of one signature element for 1024-bit DSA signatures
	private static final int DSA_SIG_ELEMENT_LENGTH = 20;
			
	// SSH compliant DSA digital signature
	private byte[] sshSignature = null;
	
	// Has ASN.1 structure been parsed?
	private boolean parsed = false;
	
	/**
	 * Constructor
	 * 
	 * @param sig - DER encoded digital signature in ASN.1 format
	 */
	public DsaSignatureAdapter(byte[] sig)
	{
		super(sig);
		this.sshSignature = null;
		this.parsed = false;
	}
	
	/**
	 * Converts the DSA digital signature from
	 * ASN.1 to the SSH compliant (IEEE P1363) format.
	 * 
	 * @return true/false, indicating success of the conversion
	 */
	public boolean convert()
	{
		// nothing to do if the signature is already available
		if ( true == sigReady )
		{
			return true;
		}
		
		// parse the ASN.1 structure
		if ( false == parsed )
		{
			parsed = parse();
			if ( false==parsed )
			{
				return false;
			}
		}
		
		// Are both signature components available?
		if ( null==r || null==s )
		{
			return false;
		}
		
		/*
		 * r and s have been parsed from the structure, now they need to be converted
		 * into the IEEE P1363 format. This format requires both components to be 
		 * 20 bytes long. If any of them is shorter, the appropriate number of zeros
		 * is padded in front of the too short component. It is also possible that 
		 * a component is one byte longer than this. This comes from the DER encoding
		 * that strictly preserves the sign of integers (defined by the most significant
		 * bit). If the MSB of the original 20-byte integer is 1, additional byte (equaling 0)
		 * is prepended the component, making the integer positive. In such a case,
		 * this byte is omitted and remaining 20 bytes are copied into the IEEE P1363 
		 * compliant signature. More info about this at:
		 * http://www.codeproject.com/Articles/25590/Cryptographic-Interoperability-Digital-Signatures#xx3240277xx
		*/
		
		// r and s must not be longer than (DSA_SIG_ELEMENT_LENGTH+1)
		// See the explanation above for more details
		if ( r.length<1 || r.length>DSA_SIG_ELEMENT_LENGTH+1 ||
			 s.length<1 || s.length>DSA_SIG_ELEMENT_LENGTH+1 )
		{
			return false;
		}
		
		// the SSH compliant signature will be exactly 40 bytes long:
		// 20 bytes for r, immediately followed by 20 bytes for s:
		sshSignature = new byte[2*DSA_SIG_ELEMENT_LENGTH];
		
		// Java should initialize the array to zeros but it doesn't hurt to do it manually as well:
		Arrays.fill(sshSignature, (byte) 0);
		
		craftSignatureElement(r, sshSignature, 0);
		craftSignatureElement(s, sshSignature, DSA_SIG_ELEMENT_LENGTH);
		
		// if reaching this point, the conversion is successful
		sigReady = true;
		return true;
	}
	
	/*
	 * Process the 'element' to be SSH compliant and copy it into the
	 * appropriate position (defined by 'destpos') of 'dest'.
	 * 
	 * @param element - array to be "processed" and copied
	 * @param dest - array where the processed 'element' will be copied to
	 * @param destpos - starting position of 'dest' where the processed 'element' will be copied
	 */
	private void craftSignatureElement(byte[] element, byte[] dest, int destpos)
	{
		// sanity check
		if ( null==element || null==dest )
		{
			return;
		}
		
		if ( (destpos + DSA_SIG_ELEMENT_LENGTH) > dest.length )
		{
			return;
		}
		
		try
		{
			System.arraycopy(
					element, 
					(element.length>DSA_SIG_ELEMENT_LENGTH ? element.length-DSA_SIG_ELEMENT_LENGTH : 0), 
					dest,
					(element.length>DSA_SIG_ELEMENT_LENGTH ? destpos : destpos+element.length-DSA_SIG_ELEMENT_LENGTH), 
					(element.length>DSA_SIG_ELEMENT_LENGTH ? DSA_SIG_ELEMENT_LENGTH : element.length) );
		}
		catch ( ArrayIndexOutOfBoundsException ex )
		{
			return;
		}
		
	}
	
	/**
	 * @return SSH compliant DSA digital signature
	 */
	public byte[] getSshSignature()
	{
		return ( true==sigReady ? sshSignature : null );
	}
}
