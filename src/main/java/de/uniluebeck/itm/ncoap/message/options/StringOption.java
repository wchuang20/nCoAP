/**
 * Copyright (c) 2012, Oliver Kleine, Institute of Telematics, University of Luebeck
 * All rights reserved
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *  - Redistributions of source messageCode must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *  - Neither the name of the University of Luebeck nor the names of its contributors may be used to endorse or promote
 *    products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.uniluebeck.itm.ncoap.message.options;

import de.uniluebeck.itm.ncoap.message.CoapMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * @author Oliver Kleine
 */
public class StringOption extends Option<String>{

    private static Logger log = LoggerFactory.getLogger(StringOption.class.getName());

    /**
     * @param optionNumber the option number of the {@link StringOption} to be created
     * @param value the value of the {@link StringOption} to be created
     *
     * @throws InvalidOptionException if the given value is either the default value or exceeds
     * the defined limits for options with the given option number in terms of length.
     *
     * @throws UnknownOptionException if the given option number is unknown, i.e. not supported
     */
    public StringOption(int optionNumber, byte[] value)
            throws InvalidOptionException, UnknownOptionException {

        super(optionNumber, value);
    }

    /**
     * Creates an instance of {@link StringOption} according to the rules defined for CoAP. The pre-processing of the
     * given value before encoding using {@link CoapMessage#CHARSET} depends on the given option number:
     *
     * <ul>
     *     <li>
     *         {@link Option.Name#URI_HOST}: convert to lower case and remove percent encoding (if present)
     *     </li>
     *     <li>
     *         {@link Option.Name#URI_PATH} and {@link Option.Name#URI_QUERY}: remove percent encoding (if present)
     *     </li>
     *     <li>
     *         others: no pre-processing.
     *     </li>
     * </ul>
     *
     * @param optionNumber the option number of the {@link StringOption} to be created
     * @param value the value of the {@link StringOption} to be created
     *
     * @throws InvalidOptionException if the given value is either the default value or (after encoding) exceeds
     * the defined limits for options with the given option number in terms of length
     * (see {@link Option#getMinLength(int)} and {@link Option#getMaxLength(int)}.
     *
     * @throws UnknownOptionException if the given option number is unknown, i.e. not supported
     *
     * @throws IllegalArgumentException if the given option value contains invalid percent encoding
     */
    public StringOption(int optionNumber, String value)
            throws InvalidOptionException, UnknownOptionException, IllegalArgumentException{

        this(optionNumber, optionNumber == Option.Name.URI_HOST ?
                convertToByteArrayWithoutPercentEncoding(value.toLowerCase(Locale.ENGLISH)) :
                ((optionNumber == Option.Name.URI_PATH || optionNumber == Option.Name.URI_QUERY) ?
                            convertToByteArrayWithoutPercentEncoding(value) :
                                    value.getBytes(CoapMessage.CHARSET)));
    }

    /**
     * Returns the decoded value of this option assuming the byte array returned by {@link #getValue()} is an encoded
     * String using {@link CoapMessage#CHARSET}.
     *
     * @return the decoded value of this option assuming the byte array returned by {@link #getValue()} is an encoded
     * String using {@link CoapMessage#CHARSET}.
     */
    @Override
    public String getDecodedValue() {
        return new String(value, CoapMessage.CHARSET);
    }

    /**
     * Replaces percent-encoding from the given {@link String} and returns a byte array containing the encoded string
     * using {@link CoapMessage#CHARSET}.
     *
     * @param s the {@link String} to be encoded
     *
     * @return a byte array containing the encoded string using {@link CoapMessage#CHARSET} without percent-encoding.
     */
    public static byte[] convertToByteArrayWithoutPercentEncoding(String s) throws IllegalArgumentException{

        ByteArrayInputStream in = new ByteArrayInputStream(s.getBytes(CoapMessage.CHARSET));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int i;
        do{
            i = in.read();
            //-1 indicates end of stream
            if(i == -1)
                break;

            //0x25 = '%'
            if(i == 0x25){
                //Character.digit returns the integer value encoded as in.read(). Since we know that percent encoding
                //uses bytes from 0x0 to 0xF (i.e. 0 to 15) the radix must be 16.
                int d1 = Character.digit(in.read(), 16);
                int d2 = Character.digit(in.read(), 16);

                if(d1 == -1 || d2 == -1){
                    //Unexpected end of stream (at least one byte missing after '%')
                    throw new IllegalArgumentException("Invalid percent encoding in: " + s);
                }

                //Write decoded value to output stream (e.g. sequence [0x02, 0x00] results into byte 0x20
                out.write((d1 << 4) | d2);
            }
            else{
                out.write(i);
            }
        } while(true);

        return out.toByteArray();
    }
}