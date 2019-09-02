package org.omg.IOP;


/**
* org/omg/IOP/ServiceContextListHolder.java .
* Generated by the IDL-to-Java compiler (portable), version "3.2"
* from c:/re/workspace/8-2-build-windows-amd64-cygwin/jdk8u161/10277/corba/src/share/classes/org/omg/PortableInterceptor/IOP.idl
* Tuesday, December 19, 2017 5:53:41 PM PST
*/


/** An array of service contexts, forming a service context list. */
public final class ServiceContextListHolder implements org.omg.CORBA.portable.Streamable
{
  public ServiceContext value[] = null;

  public ServiceContextListHolder ()
  {
  }

  public ServiceContextListHolder (ServiceContext[] initialValue)
  {
    value = initialValue;
  }

  public void _read (org.omg.CORBA.portable.InputStream i)
  {
    value = ServiceContextListHelper.read (i);
  }

  public void _write (org.omg.CORBA.portable.OutputStream o)
  {
    ServiceContextListHelper.write (o, value);
  }

  public org.omg.CORBA.TypeCode _type ()
  {
    return ServiceContextListHelper.type ();
  }

}
