package org.omg.CosNaming.NamingContextPackage;

/**
* org/omg/CosNaming/NamingContextPackage/NotFoundReasonHolder.java .
* Generated by the IDL-to-Java compiler (portable), version "3.2"
* from c:/re/workspace/8-2-build-windows-amd64-cygwin/jdk8u161/10277/corba/src/share/classes/org/omg/CosNaming/nameservice.idl
* Tuesday, December 19, 2017 5:53:41 PM PST
*/


/**
         * Indicates the reason for not able to resolve.
         */
public final class NotFoundReasonHolder implements org.omg.CORBA.portable.Streamable
{
  public NotFoundReason value = null;

  public NotFoundReasonHolder ()
  {
  }

  public NotFoundReasonHolder (NotFoundReason initialValue)
  {
    value = initialValue;
  }

  public void _read (org.omg.CORBA.portable.InputStream i)
  {
    value = NotFoundReasonHelper.read (i);
  }

  public void _write (org.omg.CORBA.portable.OutputStream o)
  {
    NotFoundReasonHelper.write (o, value);
  }

  public org.omg.CORBA.TypeCode _type ()
  {
    return NotFoundReasonHelper.type ();
  }

}