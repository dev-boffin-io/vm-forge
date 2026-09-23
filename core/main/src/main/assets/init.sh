set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi


export PS1='\[\033[01;32m\]\u@prootforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1

#fix linker warning
if [ ! -f /linkerconfig/ld.config.txt ]; then
    mkdir -p /linkerconfig 2>/dev/null || true
    touch /linkerconfig/ld.config.txt 2>/dev/null || true
fi

if [ "$#" -eq 0 ]; then
    if [ -f /etc/profile ]; then
        . /etc/profile
    fi
    export PS1='\[\033[01;32m\]\u@prootforge\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
    cd $HOME
    if [ -f /initrc ]; then
        . /initrc
    fi
    if [ -x /bin/bash ]; then
        /bin/bash
    else
        /bin/sh
    fi
else
    exec "$@"
fi